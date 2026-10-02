/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.device.PowerManagementService
import com.nextcloud.client.jobs.InternalTwoWaySyncWork
import com.nextcloud.client.network.Connectivity
import com.nextcloud.client.network.ConnectivityService
import com.nextcloud.client.preferences.AppPreferences
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

internal class InternalTwoWayWorkerFailureTest : SubtreeSyncTest() {
    @Test
    fun failingRootIsRecordedAndDoesNotPreventTheNextRootFromRunning() {
        fixture.syncTree()
        val accounts = mock<UserAccountManager>()
        val power = mock<PowerManagementService>()
        val connectivity = mock<ConnectivityService>()
        val preferences = mock<AppPreferences>()
        whenever(accounts.allUsers).thenReturn(listOf(fixture.user))
        whenever(connectivity.isConnected).thenReturn(true)
        whenever(connectivity.connectivity).thenReturn(Connectivity.CONNECTED_WIFI)
        whenever(preferences.isTwoWaySyncEnabled).thenReturn(true)
        val rootPath = FolderSyncFixture.ROOT
        val first = fixture.local.getValue(rootPath).apply {
            storagePath = temporaryFolder.root.resolve("not-downloaded").path
        }
        val next = fixture.local.getValue(rootPath + "a/").apply {
            storagePath = temporaryFolder.root.resolve("not-downloaded").path
        }
        val success = RemoteOperationResult<Any>(RemoteOperationResult.ResultCode.OK)
        val exception = IllegalStateException("invalid root metadata")
        mockConstruction(FileDataStorageManager::class.java) { storage, _ ->
            whenever(storage.getInternalTwoWaySyncFolders(fixture.user)).thenReturn(listOf(first, next))
        }.use { stores ->
            mockConstruction(SynchronizeFolderOperation::class.java) { operation, construction ->
                if (construction.arguments()[1] == rootPath) {
                    whenever(operation.execute(fixture.context)).thenThrow(exception)
                } else {
                    whenever(operation.execute(fixture.context)).thenReturn(success)
                }
            }.use { operations ->
                val worker = InternalTwoWaySyncWork(
                    fixture.context,
                    mock<WorkerParameters>(),
                    accounts,
                    power,
                    connectivity,
                    preferences
                )
                assertEquals(ListenableWorker.Result.failure(), worker.doWork())
                assertEquals(2, operations.constructed().size)
                verify(operations.constructed()[1]).execute(fixture.context)
                val storage = stores.constructed().single()
                val failureCode = RemoteOperationResult<Any>(exception).code.toString()
                verify(storage).updateInternalSyncResult(eq(first), any(), eq(failureCode))
                verify(storage).updateInternalSyncResult(eq(next), any(), eq("OK"))
            }
        }
    }
}
