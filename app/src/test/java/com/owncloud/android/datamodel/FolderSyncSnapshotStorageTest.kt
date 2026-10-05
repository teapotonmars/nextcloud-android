/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.datamodel

import com.nextcloud.client.account.User
import com.nextcloud.client.database.dao.FileDao
import com.owncloud.android.lib.common.network.WebdavEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class FolderSyncSnapshotStorageTest {
    @Test
    fun certificatesArePersistentAndIsolatedByAccountAndPath() {
        val rows = mutableMapOf<Pair<String, Long>, String>()
        val dao = mock<FileDao>()
        whenever(dao.getFolderSyncSnapshot(any(), any())).thenAnswer {
            rows[it.getArgument<String>(0) to it.getArgument<Long>(1)]
        }
        doAnswer {
            rows[it.getArgument<String>(0) to it.getArgument<Long>(1)] = it.getArgument(2)
            null
        }.whenever(dao).setFolderSyncSnapshot(any(), any(), any())
        val first = storage("first-account", dao)
        val second = storage("second-account", dao)
        first.saveFolderSyncSnapshot("/root/", "first-state")
        second.saveFolderSyncSnapshot("/root/", "second-state")
        assertEquals("first-state", first.getFolderSyncSnapshot("/root/"))
        assertEquals("second-state", second.getFolderSyncSnapshot("/root/"))
        assertEquals(null, first.getFolderSyncSnapshot("/other/"))
        assertEquals("first-state", storage("first-account", dao).getFolderSyncSnapshot("/root/"))
    }

    @Test
    fun descendantsOfExternalMountNeverReadOrWriteCertificates() {
        val storage = storage("account")
        whenever(storage.getFileByPath("/")).thenReturn(
            directory("/").apply {
                mountType = WebdavEntry.MountType.EXTERNAL
            }
        )
        storage.saveFolderSyncSnapshot("/root/", "ignored")
        assertEquals("", storage.getFolderSyncSnapshot("/root/"))
    }

    private fun storage(account: String, dao: FileDao = mock()): FileDataStorageManager {
        val user = mock<User>()
        whenever(user.accountName).thenReturn(account)
        val storage = mock<FileDataStorageManager>()
        val field = FileDataStorageManager::class.java.getDeclaredField("user")
        field.isAccessible = true
        field.set(storage, user)
        FileDataStorageManager::class.java.getDeclaredField("fileDao").apply {
            isAccessible = true
            set(storage, dao)
        }
        whenever(storage.getFileByPath(any())).thenAnswer { directory(it.getArgument(0)) }
        whenever(storage.getFolderSyncSnapshot(any())).thenCallRealMethod()
        doAnswer { it.callRealMethod() }.whenever(storage).saveFolderSyncSnapshot(any(), any())
        return storage
    }

    private fun directory(path: String) = OCFile(path).apply {
        mimeType = "httpd/unix-directory"
        fileId = if (path == "/root/") 1 else 2
    }
}
