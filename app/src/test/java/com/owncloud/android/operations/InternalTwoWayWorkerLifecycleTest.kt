/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.nextcloud.client.account.User
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.device.PowerManagementService
import com.nextcloud.client.jobs.InternalTwoWaySyncWork
import com.nextcloud.client.network.Connectivity
import com.nextcloud.client.network.ConnectivityService
import com.nextcloud.client.preferences.AppPreferences
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.common.utils.Log_OC
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class InternalTwoWayWorkerLifecycleTest {
    private val context = mock<Context>()
    private val user = mock<User>()
    private val accounts = mock<UserAccountManager>()
    private val power = mock<PowerManagementService>()
    private val connectivity = mock<ConnectivityService>()
    private val preferences = mock<AppPreferences>()
    private val first = folder("/first/")
    private val second = folder("/second/")
    private lateinit var worker: InternalTwoWaySyncWork

    @Test
    fun failedRootIsRecordedAndTheNextRootStillRuns() = withWorker { stores ->
        val exception = IllegalStateException("invalid root metadata")
        mockConstruction(SynchronizeFolderOperation::class.java) { operation, construction ->
            if (construction.arguments()[1] == "/first/") {
                whenever(operation.execute(context)).thenThrow(exception)
            } else {
                whenever(operation.execute(context)).thenReturn(result(RemoteOperationResult.ResultCode.OK))
            }
        }.use { operations ->
            assertEquals(ListenableWorker.Result.failure(), worker.doWork())
            assertEquals(2, operations.constructed().size)
            verify(operations.constructed()[1]).execute(context)
            val storage = stores().single()
            val failureCode = RemoteOperationResult<Any>(exception).code.toString()
            verify(storage).updateInternalSyncResult(eq(first), any(), eq(failureCode))
            verify(storage).updateInternalSyncResult(eq(second), any(), eq("OK"))
        }
    }

    @Test
    fun stopBeforeWorkPreventsAllOperationsAndResultWrites() = withWorker { stores ->
        worker.onStopped()
        mockConstruction(SynchronizeFolderOperation::class.java).use { operations ->
            assertEquals(ListenableWorker.Result.failure(), worker.doWork())
            assertEquals(0, operations.constructed().size)
            verify(stores().single(), never()).updateInternalSyncResult(any(), any(), any())
        }
    }

    @Test
    fun stopDuringOperationConstructionCancelsItBeforeExecution() = withWorker { stores ->
        mockConstruction(SynchronizeFolderOperation::class.java) { _, _ -> worker.onStopped() }.use { operations ->
            assertEquals(ListenableWorker.Result.failure(), worker.doWork())
            val operation = operations.constructed().single()
            verify(operation).cancel()
            verify(operation, never()).execute(context)
            verify(stores().single()).updateInternalSyncResult(eq(first), any(), eq("CANCELLED"))
        }
    }

    @Test
    fun stopDuringExecutionCancelsTheActiveOperationAndPreventsLaterRoots() = withWorker { stores ->
        mockConstruction(SynchronizeFolderOperation::class.java) { operation, _ ->
            whenever(operation.execute(context)).thenAnswer {
                worker.onStopped()
                result(RemoteOperationResult.ResultCode.CANCELLED)
            }
        }.use { operations ->
            assertEquals(ListenableWorker.Result.failure(), worker.doWork())
            verify(operations.constructed().single()).cancel()
            verify(stores().single(), never()).updateInternalSyncResult(eq(second), any(), any())
        }
    }

    private fun withWorker(test: (() -> List<FileDataStorageManager>) -> Unit) {
        mockStatic(Log_OC::class.java).use {
            whenever(accounts.allUsers).thenReturn(listOf(user))
            whenever(connectivity.isConnected).thenReturn(true)
            whenever(connectivity.connectivity).thenReturn(Connectivity.CONNECTED_WIFI)
            whenever(preferences.isTwoWaySyncEnabled).thenReturn(true)
            mockConstruction(FileDataStorageManager::class.java) { storage, _ ->
                whenever(storage.getInternalTwoWaySyncFolders(user)).thenReturn(listOf(first, second))
            }.use { stores ->
                worker = InternalTwoWaySyncWork(
                    context,
                    mock<WorkerParameters>(),
                    accounts,
                    power,
                    connectivity,
                    preferences
                )
                test { stores.constructed() }
            }
        }
    }

    private fun folder(path: String): OCFile = mock<OCFile>().also {
        whenever(it.remotePath).thenReturn(path)
        whenever(it.storagePath).thenReturn("/nonexistent-nextcloud-worker-regression")
    }

    private fun result(code: RemoteOperationResult.ResultCode) = RemoteOperationResult<Any>(code)
}
