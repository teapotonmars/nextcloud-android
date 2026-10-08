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
import com.owncloud.android.MainApp
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.utils.FileStorageUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class InternalSyncRootFairnessTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val context = mock<Context>()
    private val user = mock<User>()
    private val accounts = mock<UserAccountManager>()
    private val power = mock<PowerManagementService>()
    private val connectivity = mock<ConnectivityService>()
    private val preferences = mock<AppPreferences>()
    private val roots = listOf(folder(1, 300), folder(2, 200), folder(3, 100))
    private var resumeAfter: String? = null
    private lateinit var worker: InternalTwoWaySyncWork

    @Test
    fun rootWithoutEnoughSpaceDoesNotBlockLaterRootsOnRepeatedRuns() = withStores { stores ->
        whenever(roots[0].storagePath).thenReturn(temporaryFolder.root.path)
        whenever(roots[0].fileLength).thenReturn(Long.MAX_VALUE)
        val abandoned = temporaryFolder.root.resolve("download-${UUID.randomUUID()}/partial.txt").apply {
            parentFile!!.mkdirs()
            writeText("abandoned")
        }
        val attempted = mutableListOf<String>()
        mockStatic(MainApp::class.java).use { mainApp ->
            mainApp.`when`<String> { MainApp.getDataFolder() }.thenReturn("nextcloud")
            mockConstruction(SynchronizeFolderOperation::class.java) { operation, construction ->
                whenever(operation.execute(context)).thenAnswer {
                    attempted.add(construction.arguments()[1] as String)
                    result(RemoteOperationResult.ResultCode.OK)
                }
            }.use {
                repeat(2) {
                    worker = newWorker()
                    assertEquals(ListenableWorker.Result.failure(), worker.doWork())
                    assertEquals(null, resumeAfter)
                }
            }
        }
        assertFalse(abandoned.exists())
        assertEquals(List(2) { listOf(roots[1].remotePath, roots[2].remotePath) }.flatten(), attempted)
        stores().forEach { storage ->
            verify(storage).updateInternalSyncResult(eq(roots[0]), isNull(), eq("LOCAL_STORAGE_FULL"))
            verify(storage, never()).updateInternalSyncResult(eq(roots[0]), any(), any())
        }
    }

    @Test
    fun successiveInterruptionsGiveEveryRootATurnWithoutChangingSyncTimestamps() = withStores { stores ->
        val attempted = mutableListOf<String>()
        repeat(roots.size) {
            mockConstruction(SynchronizeFolderOperation::class.java) { operation, construction ->
                whenever(operation.execute(context)).thenAnswer {
                    val path = construction.arguments()[1] as String
                    attempted.add(path)
                    assertEquals(roots.single { it.remotePath == path }.fileId.toString(), resumeAfter)
                    worker.onStopped()
                    result(RemoteOperationResult.ResultCode.CANCELLED)
                }
            }.use {
                worker = newWorker()
                assertEquals(ListenableWorker.Result.failure(), worker.doWork())
            }
        }
        assertEquals(roots.map { it.remotePath }, attempted)
        roots.zip(stores()).forEach { (root, storage) ->
            verify(storage).updateInternalSyncResult(eq(root), isNull(), eq("CANCELLED"))
            verify(storage, never()).getFileByPath(any())
        }
        assertEquals(listOf(300L, 200L, 100L), roots.map { it.internalFolderSyncTimestamp })
    }

    @Test
    fun completedPassStartsAfterInterruptedRootThenWrapsAndClearsTheCursor() = withStores {
        resumeAfter = roots.first().fileId.toString()
        val attempted = mutableListOf<String>()
        mockConstruction(SynchronizeFolderOperation::class.java) { operation, construction ->
            whenever(operation.execute(context)).thenAnswer {
                attempted.add(construction.arguments()[1] as String)
                result(RemoteOperationResult.ResultCode.OK)
            }
        }.use {
            worker = newWorker()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        }
        assertEquals(listOf(roots[1].remotePath, roots[2].remotePath, roots[0].remotePath), attempted)
        assertEquals(null, resumeAfter)
    }

    @Test
    fun removedResumeRootFallsBackToTheCurrentRootOrder() = withStores {
        resumeAfter = "removed-root"
        val attempted = mutableListOf<String>()
        mockConstruction(SynchronizeFolderOperation::class.java) { operation, construction ->
            whenever(operation.execute(context)).thenAnswer {
                attempted.add(construction.arguments()[1] as String)
                result(RemoteOperationResult.ResultCode.OK)
            }
        }.use {
            worker = newWorker()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        }
        assertEquals(roots.map { it.remotePath }, attempted)
    }

    @Test
    fun interruptedRootDoesNotStarveRootsInAnotherAccount() {
        val otherUser = mock<User>()
        withStores(listOf(user, otherUser)) {
            val attempted = mutableListOf<String>()
            repeat(2) {
                mockConstruction(SynchronizeFolderOperation::class.java) { operation, construction ->
                    whenever(operation.execute(context)).thenAnswer {
                        attempted.add(construction.arguments()[1] as String)
                        worker.onStopped()
                        result(RemoteOperationResult.ResultCode.CANCELLED)
                    }
                }.use {
                    worker = newWorker()
                    assertEquals(ListenableWorker.Result.failure(), worker.doWork())
                }
            }
            assertEquals(listOf(roots[0].remotePath, roots[1].remotePath), attempted)
        }
    }

    private fun withStores(users: List<User> = listOf(user), test: (() -> List<FileDataStorageManager>) -> Unit) {
        mockStatic(FileStorageUtils::class.java).use { fileUtils ->
            fileUtils.`when`<String> { FileStorageUtils.getTemporalPath(user.accountName) }
                .thenReturn(temporaryFolder.root.path)
            mockStatic(Log_OC::class.java).use {
                whenever(accounts.allUsers).thenReturn(users)
                whenever(connectivity.isConnected).thenReturn(true)
                whenever(connectivity.connectivity).thenReturn(Connectivity.CONNECTED_WIFI)
                whenever(preferences.isTwoWaySyncEnabled).thenReturn(true)
                whenever(preferences.internalSyncResumeAfterRoot).thenAnswer { resumeAfter }
                doAnswer {
                    resumeAfter = it.getArgument(0)
                    null
                }
                    .whenever(preferences).setInternalSyncResumeAfterRoot(org.mockito.kotlin.anyOrNull())
                mockConstruction(FileDataStorageManager::class.java) { storage, construction ->
                    val enrolled = if (users.size == 1) {
                        roots
                    } else {
                        listOf(
                            if (construction.arguments()[0] === user) roots[0] else roots[1]
                        )
                    }
                    whenever(storage.getInternalTwoWaySyncFolders(any())).thenReturn(enrolled)
                    whenever(storage.isInternalSyncEnrolled(any())).thenReturn(true)
                }.use { stores -> test { stores.constructed() } }
            }
        }
    }

    private fun newWorker() = InternalTwoWaySyncWork(
        context,
        mock<WorkerParameters>(),
        accounts,
        power,
        connectivity,
        preferences
    )

    private fun folder(id: Long, timestamp: Long) = mock<OCFile>().also {
        whenever(it.fileId).thenReturn(id)
        whenever(it.remotePath).thenReturn("/root$id/")
        whenever(it.internalFolderSyncTimestamp).thenReturn(timestamp)
        whenever(it.storagePath).thenReturn("/nonexistent-nextcloud-worker-regression")
    }

    private fun result(code: RemoteOperationResult.ResultCode) = RemoteOperationResult<Any>(code)
}
