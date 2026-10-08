/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.services

import android.accounts.Account
import com.nextcloud.client.jobs.download.FileDownloadEventBroadcaster
import com.owncloud.android.files.services.IndexedForest
import com.owncloud.android.operations.FolderSyncMode
import com.owncloud.android.operations.SynchronizeFolderOperation
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncFolderDeduplicationTest {
    @Test
    fun forcedRecursiveRequestReplacesPendingCachedChild() {
        assertForcedRequestReplaces(FolderSyncMode.RECURSIVE_CACHED)
    }

    @Test
    fun forcedRecursiveRequestReplacesPendingOrdinarySync() {
        assertForcedRequestReplaces(FolderSyncMode.SINGLE_FOLDER)
    }

    @Test
    fun ordinaryRequestCannotDowngradeForcedSync() {
        val pending = IndexedForest<SynchronizeFolderOperation>()
        val forced = operation(FolderSyncMode.RECURSIVE_FORCED)
        pending.putIfAbsent(ACCOUNT, PATH, forced)
        handler(pending).add(account(), PATH, operation(FolderSyncMode.SINGLE_FOLDER))
        assertSame(forced, pending.get(ACCOUNT, PATH))
    }

    @Test
    fun activeOperationCleanupRetainsForcedReplacementAndChildren() {
        val pending = IndexedForest<SynchronizeFolderOperation>()
        val active = operation(FolderSyncMode.RECURSIVE_CACHED)
        val forced = operation(FolderSyncMode.RECURSIVE_FORCED)
        val child = operation(FolderSyncMode.RECURSIVE_CACHED)
        pending.putIfAbsent(ACCOUNT, PATH, active)
        pending.putIfAbsent(ACCOUNT, PATH + "child/", child)
        handler(pending).add(account(), PATH, forced)
        pending.removePayload(ACCOUNT, PATH, active)
        assertSame(forced, pending.get(ACCOUNT, PATH))
        assertSame(child, pending.get(ACCOUNT, PATH + "child/"))
    }

    @Test
    fun recursiveRequestReplacesPendingOrdinarySync() {
        val pending = IndexedForest<SynchronizeFolderOperation>()
        pending.putIfAbsent(ACCOUNT, PATH, operation(FolderSyncMode.SINGLE_FOLDER))
        val recursive = operation(FolderSyncMode.RECURSIVE_CACHED)
        handler(pending).add(account(), PATH, recursive)
        assertSame(recursive, pending.get(ACCOUNT, PATH))
    }

    private fun assertForcedRequestReplaces(mode: FolderSyncMode) {
        val pending = IndexedForest<SynchronizeFolderOperation>()
        pending.putIfAbsent(ACCOUNT, PATH, operation(mode))
        val forced = operation(FolderSyncMode.RECURSIVE_FORCED)
        handler(pending).add(account(), PATH, forced)
        assertSame(forced, pending.get(ACCOUNT, PATH))
    }

    private fun handler(pending: IndexedForest<SynchronizeFolderOperation>): SyncFolderHandler =
        mock<SyncFolderHandler>(defaultAnswer = CALLS_REAL_METHODS).also {
            setField(it, "mPendingOperations", pending)
            setField(it, "mService", mock<OperationsService>())
            setField(it, "fileDownloadEventBroadcaster", mock<FileDownloadEventBroadcaster>())
        }

    private fun setField(handler: SyncFolderHandler, name: String, value: Any) {
        SyncFolderHandler::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(handler, value)
        }
    }

    private fun operation(mode: FolderSyncMode): SynchronizeFolderOperation = mock<SynchronizeFolderOperation>().also {
        whenever(it.syncMode).thenReturn(mode)
    }

    private fun account(): Account = mock<Account>().also {
        Account::class.java.getDeclaredField("name").apply {
            isAccessible = true
            set(it, ACCOUNT)
        }
    }

    companion object {
        private const val ACCOUNT = "account"
        private const val PATH = "/Books/Nested/"
    }
}
