/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2024 Tobias Kaminsky <tobias.kaminsky@nextcloud.com>
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.nextcloud.client.jobs

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.nextcloud.client.account.User
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.device.PowerManagementService
import com.nextcloud.client.network.ConnectivityService
import com.nextcloud.client.preferences.AppPreferences
import com.owncloud.android.MainApp
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.operations.SynchronizeFolderOperation
import com.owncloud.android.utils.FileStorageUtils
import java.io.File
import java.util.ArrayDeque
import java.util.function.BooleanSupplier

@Suppress("Detekt.NestedBlockDepth", "ReturnCount", "LongParameterList")
class InternalTwoWaySyncWork(
    private val context: Context,
    params: WorkerParameters,
    private val userAccountManager: UserAccountManager,
    private val powerManagementService: PowerManagementService,
    private val connectivityService: ConnectivityService,
    private val appPreferences: AppPreferences
) : Worker(context, params) {
    @Volatile
    private var shouldRun = true
    private val operationLock = Any()
    private var operation: SynchronizeFolderOperation? = null

    override fun doWork(): Result {
        Log_OC.d(TAG, "Worker started!")

        var result = true

        if (!constraintsAllowSync()) {
            Log_OC.d(TAG, "Not starting due to constraints!")
            return Result.success()
        }

        val roots = userAccountManager.allUsers.flatMap { user ->
            val storage = FileDataStorageManager(user, context.contentResolver)
            storage.getInternalTwoWaySyncFolders(user).map { InternalSyncRoot(user, it, storage) }
        }
        val resumeAfter = appPreferences.internalSyncResumeAfterRoot
        val interruptedIndex = roots.indexOfFirst { it.folder.fileId.toString() == resumeAfter }
        val orderedRoots = roots.drop(interruptedIndex + 1) + roots.take(interruptedIndex + 1)

        for ((user, folder, storage) in orderedRoots) {
            if (!shouldRun || !constraintsAllowSync()) {
                Log_OC.d(TAG, "Worker was stopped!")
                return Result.failure()
            }

            checkFreeSpace(folder)?.let { return it }
            appPreferences.internalSyncResumeAfterRoot = folder.fileId.toString()
            Log_OC.d(TAG, "Folder ${folder.remotePath}: started!")
            val operationResult = synchronizeFolder(user, folder, storage)

            if (operationResult.isSuccess) {
                Log_OC.d(TAG, "Folder ${folder.remotePath}: finished!")
            } else {
                Log_OC.d(TAG, "Folder ${folder.remotePath} failed!")
                result = false
            }

            saveSyncResult(storage, folder, operationResult)
            if (!shouldRun || !constraintsAllowSync()) return Result.failure()
        }
        appPreferences.internalSyncResumeAfterRoot = null

        return if (result) {
            Log_OC.d(TAG, "Worker finished with success!")
            Result.success()
        } else {
            Log_OC.d(TAG, "Worker finished with failure!")
            Result.failure()
        }
    }

    private fun synchronizeFolder(
        user: User,
        folder: OCFile,
        storage: FileDataStorageManager
    ): RemoteOperationResult<*> {
        val pending = ArrayDeque<String>().apply { add(folder.remotePath) }
        val visited = mutableSetOf<String>()
        var result: RemoteOperationResult<*> = RemoteOperationResult<Any>(RemoteOperationResult.ResultCode.OK)
        while (pending.isNotEmpty()) {
            val path = pending.removeFirst()
            if (!visited.add(path)) continue
            val nextResult = executeFolder(user, path, folder, storage, pending)
            if (!nextResult.isSuccess) result = nextResult
            if (nextResult.code == RemoteOperationResult.ResultCode.CANCELLED) return nextResult
            if (!shouldRun || !constraintsAllowSync()) {
                return RemoteOperationResult<Any>(RemoteOperationResult.ResultCode.CANCELLED)
            }
        }
        return result
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeFolder(
        user: User,
        path: String,
        root: OCFile,
        storage: FileDataStorageManager,
        pending: ArrayDeque<String>
    ): RemoteOperationResult<*> = try {
        val nextOperation = SynchronizeFolderOperation(
            context,
            path,
            user,
            storage,
            false,
            // Metadata refreshes can cache ancestor ETags before descendants have been synchronized.
            true
        )
        nextOperation.setRecursiveChild(path != root.remotePath)
        val syncAllowed = BooleanSupplier {
            shouldRun && constraintsAllowSync() &&
                storage.isInternalSyncEnrolled(root)
        }
        nextOperation.setWorkerTraversal({ pending.add(it) }, syncAllowed)
        val canRun = synchronized(operationLock) {
            if (syncAllowed.asBoolean) {
                operation = nextOperation
                true
            } else {
                nextOperation.cancel()
                false
            }
        }
        if (canRun) {
            nextOperation.execute(context)
        } else {
            RemoteOperationResult<Any>(RemoteOperationResult.ResultCode.CANCELLED)
        }
    } catch (exception: RuntimeException) {
        Log_OC.e(TAG, "Folder $path: synchronization threw an exception", exception)
        RemoteOperationResult<Any>(exception)
    }

    private fun constraintsAllowSync(): Boolean =
        appPreferences.isTwoWaySyncEnabled && !powerManagementService.isPowerSavingEnabled &&
            connectivityService.isConnected && !connectivityService.isInternetWalled() &&
            connectivityService.connectivity.isWifi

    private fun saveSyncResult(storage: FileDataStorageManager, folder: OCFile, result: RemoteOperationResult<*>?) {
        val timestamp = when (result?.code) {
            RemoteOperationResult.ResultCode.CANCELLED -> null
            else -> System.currentTimeMillis()
        }
        storage.updateInternalSyncResult(folder, timestamp, result?.code?.toString())
    }

    override fun onStopped() {
        Log_OC.d(TAG, "OnStopped of worker called!")
        synchronized(operationLock) {
            shouldRun = false
            operation?.cancel()
        }
        super.onStopped()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun checkFreeSpace(folder: OCFile): Result? {
        val storagePath = folder.storagePath ?: MainApp.getStoragePath()
        val file = File(storagePath)

        if (!file.exists()) return null

        return try {
            val freeSpaceLeft = file.freeSpace
            val localFolder = File(storagePath, MainApp.getDataFolder())
            val localFolderSize = FileStorageUtils.getFolderSize(localFolder)
            val remoteFolderSize = folder.fileLength

            if (freeSpaceLeft < (remoteFolderSize - localFolderSize)) {
                Log_OC.d(TAG, "Not enough space left!")
                Result.failure()
            } else {
                null
            }
        } catch (e: Exception) {
            Log_OC.d(TAG, "Error caught at checkFreeSpace: $e")
            null
        }
    }

    companion object {
        const val TAG = "InternalTwoWaySyncWork"
    }
}
