/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.services

import android.accounts.Account
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.files.services.IndexedForest
import com.owncloud.android.operations.SynchronizeFolderOperation
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class SyncFolderCancellationTest {
    @Test
    fun cancellingStructuralAncestorStopsItsActiveDescendant() {
        val operation = operation(ACCOUNT)
        val pending = IndexedForest<SynchronizeFolderOperation>()
        pending.putIfAbsent(ACCOUNT, CHILD, operation)
        handler(pending, operation).cancel(account(), folder())
        verify(operation).cancel()
        assertEquals(0, pending.all.size)
    }

    @Test
    fun cancellationDoesNotStopAnotherAccountsActiveOperation() {
        val operation = operation(OTHER_ACCOUNT)
        val pending = IndexedForest<SynchronizeFolderOperation>()
        pending.putIfAbsent(OTHER_ACCOUNT, CHILD, operation)
        handler(pending, operation).cancel(account(), folder())
        verify(operation, never()).cancel()
        assertEquals(operation, pending.get(OTHER_ACCOUNT, CHILD))
    }

    private fun handler(
        pending: IndexedForest<SynchronizeFolderOperation>,
        current: SynchronizeFolderOperation
    ): SyncFolderHandler = mock<SyncFolderHandler>(defaultAnswer = CALLS_REAL_METHODS).also {
        SyncFolderHandler::class.java.getDeclaredField("mPendingOperations").apply {
            isAccessible = true
            set(it, pending)
        }
        SyncFolderHandler::class.java.getDeclaredField("mCurrentSyncOperation").apply {
            isAccessible = true
            set(it, current)
        }
    }

    private fun operation(account: String): SynchronizeFolderOperation = mock<SynchronizeFolderOperation>().also {
        whenever(it.remotePath).thenReturn(CHILD)
        whenever(it.accountName).thenReturn(account)
    }

    private fun account(): Account = mock<Account>().also {
        Account::class.java.getDeclaredField("name").apply {
            isAccessible = true
            set(it, ACCOUNT)
        }
    }

    private fun folder(): OCFile = mock<OCFile>().also {
        whenever(it.remotePath).thenReturn(PARENT)
    }

    companion object {
        private const val ACCOUNT = "account"
        private const val OTHER_ACCOUNT = "other"
        private const val PARENT = "/parent/"
        private const val CHILD = "/parent/child/"
    }
}
