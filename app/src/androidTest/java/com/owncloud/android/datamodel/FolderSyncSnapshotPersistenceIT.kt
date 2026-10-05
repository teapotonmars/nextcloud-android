/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.datamodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nextcloud.client.account.User
import com.nextcloud.client.database.NextcloudDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FolderSyncSnapshotPersistenceIT {
    private lateinit var storage: FileDataStorageManager
    private lateinit var root: OCFile
    private lateinit var account: String

    @Before
    fun setUp() {
        account = "folder-inventory-${UUID.randomUUID()}"
        val user = mock<User>()
        whenever(user.accountName).thenReturn(account)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        storage = FileDataStorageManager(user, context.contentResolver)
        root = directory("/")
        storage.saveFile(root)
    }

    @After
    fun tearDown() {
        storage.removeFolder(root, true, false)
    }

    @Test
    fun certificateUsesRealPersistedInventoryAndFollowsFolderLifecycle() {
        val child = directory("/child/").apply { etagOnServer = "child-token" }
        storage.saveFolder(root, listOf(child), emptyList())
        val savedRoot = storage.getFileByPath("/")
        val snapshot = FolderSyncSnapshot(
            "root-token",
            FolderSyncSnapshot.fingerprint(
                savedRoot,
                storage.getFolderContent(savedRoot, false)
            ),
            System.currentTimeMillis()
        )
        storage.saveFolderSyncSnapshot("/", snapshot.encode())
        val restored = FolderSyncSnapshot.decode(storage.getFolderSyncSnapshot("/"))!!
        assertTrue(restored.matches(savedRoot, storage.getFolderContent(savedRoot, false), System.currentTimeMillis()))
        storage.saveFolderSyncSnapshot("/child/", "child-record")
        val savedChild = storage.getFileByPath("/child/")
        assertEquals("child-record", storage.getFolderSyncSnapshot("/child/"))
        storage.removeFolder(savedChild, true, false)
        assertNull(NextcloudDatabase.instance().fileDao().getFolderSyncSnapshot(account, savedChild.fileId))
        val recreated = directory("/child/").apply { parentId = root.fileId }
        storage.saveFile(recreated)
        assertNull(storage.getFolderSyncSnapshot("/child/"))
    }

    private fun directory(path: String) = OCFile(path).apply { mimeType = "httpd/unix-directory" }
}
