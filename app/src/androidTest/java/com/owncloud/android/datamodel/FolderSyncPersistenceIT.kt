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
import com.owncloud.android.db.ProviderMeta.ProviderTableMeta
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FolderSyncPersistenceIT {
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
    fun folderSavePreservesListingEtagAndSyncTimestampUpdatePreservesFreshMetadata() {
        root.apply {
            etag = "listed-token"
            permissions = "current-permissions"
            remoteId = "00000001test"
        }
        storage.saveFolder(root, emptyList(), emptyList())
        val stale = directory("/").apply {
            etag = "unlisted-token"
            fileId = root.fileId
        }
        storage.saveFile(stale)
        assertEquals("listed-token", storage.getFileByPath("/").etag)
        storage.saveFolder(root, emptyList(), emptyList())
        storage.updateFolderSyncTime(stale, 1234)
        val saved = storage.getFileByPath("/")
        assertEquals("current-permissions", saved.permissions)
        assertEquals("00000001test", saved.remoteId)
        assertEquals("listed-token", saved.etag)
        assertEquals(1234L, saved.lastSyncDateForData)
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

    @Test
    fun conflictRepairTreatsWildcardsAndCaseAsLiteralPathCharacters() {
        val folder = directory("/Books_%/").apply { parentId = root.fileId }
        storage.saveFile(folder)
        folder.etagInConflict = "orphan"
        storage.saveFile(folder)
        for (path in listOf("/books_%/conflict", "/Books_AX/conflict")) {
            val outside = OCFile(path).apply {
                mimeType = "text/plain"
                etagInConflict = "other-conflict"
                parentId = root.fileId
            }
            storage.saveFile(outside)
        }
        storage.clearFolderConflictIfResolved(folder)
        assertFalse(storage.getFileByPath(folder.remotePath).isInConflict)
        folder.etagInConflict = "real"
        storage.saveFile(folder)
        val inside = OCFile(folder.remotePath + "conflict").apply {
            mimeType = "text/plain"
            etagInConflict = "inside-conflict"
            parentId = folder.fileId
        }
        storage.saveFile(inside)
        storage.clearFolderConflictIfResolved(folder)
        assertTrue(storage.getFileByPath(folder.remotePath).isInConflict)
    }

    @Test
    fun unchangedFolderListingsPreserveEveryPersistedColumn() {
        val path = "/token/"
        fun remoteRow(id: Long) = directory(path).apply {
            fileId = id
            parentId = root.fileId
            etag = "listed-token"
            etagOnServer = etag
            fileLength = 4096
            remoteId = "00000001test"
            permissions = "RDNVW"
            isFavorite = true
            isSharedViaLink = true
            isSharedWithSharee = true
            richWorkspace = "workspace"
            decryptedRemotePath = path
        }
        val local = remoteRow(-1)
        storage.saveFile(local)
        storage.saveFolder(local, emptyList(), emptyList())
        storage.updateFolderSize(local)
        local.apply {
            decryptedRemotePath = "/Readable/"
            isEncrypted = true
            isReadOnly = true
            lastSyncDateForProperties = 123
            lastSyncDateForData = 124
            modificationTimestampAtLastSyncForData = 125
            internalFolderSyncTimestamp = 126
            internalFolderSyncResult = "OK"
            etagInConflict = "conflict-marker"
            sharees = listOf(
                com.owncloud.android.lib.resources.shares.ShareeUser(
                    "alice",
                    "Alice",
                    com.owncloud.android.lib.resources.shares.ShareType.USER
                )
            )
        }
        storage.saveFile(local)
        NextcloudDatabase.instance().fileDao().setFolderSyncSnapshot(account, local.fileId, "inventory-marker")
        val before = readRow(path)
        repeat(2) {
            val current = storage.getFileByPath(path)
            val remote = remoteRow(current.fileId)
            storage.saveSynchronizedFolder(remote, current, emptyList(), emptyList())
            assertEquals(before, readRow(path))
            assertEquals(4096L, storage.getFileByPath(path).fileLength)
        }
    }

    private fun readRow(path: String): Map<String, String?> {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        return resolver.query(
            ProviderTableMeta.CONTENT_URI_FILE,
            null,
            "${ProviderTableMeta.FILE_ACCOUNT_OWNER}=? AND ${ProviderTableMeta.FILE_PATH}=?",
            arrayOf(account, path),
            null
        )!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)) }
        }
    }

    private fun directory(path: String) = OCFile(path).apply { mimeType = "httpd/unix-directory" }
}
