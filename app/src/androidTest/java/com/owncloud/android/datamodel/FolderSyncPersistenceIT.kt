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
import com.owncloud.android.db.ProviderMeta.ProviderTableMeta
import com.owncloud.android.lib.resources.files.model.RemoteFile
import com.owncloud.android.utils.FileStorageUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun targetedFolderWritesNotifyContentObservers() {
        root.internalFolderSyncTimestamp = 0
        storage.saveFile(root)
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        for (write in listOf<() -> Unit>(
            { storage.updateFolderSize(root) },
            { storage.updateFolderSyncTime(root, 1234) }
        )) {
            val changed = java.util.concurrent.CountDownLatch(1)
            val observer = object : android.database.ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    changed.countDown()
                }
            }
            resolver.registerContentObserver(ProviderTableMeta.CONTENT_URI, true, observer)
            try {
                write()
                assertTrue(changed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            } finally {
                resolver.unregisterContentObserver(observer)
            }
        }
    }

    @Test
    fun unchangedFolderListingsPreserveEveryPersistedColumn() {
        val path = "/token/"
        fun remoteRow(id: Long): OCFile {
            val remote = RemoteFile(path).apply {
                mimeType = "httpd/unix-directory"
                etag = "listed-token"
            }
            return FileStorageUtils.fillOCFile(remote).apply {
                fileId = id
                parentId = root.fileId
                etag = "listed-token"
                fileLength = 4096
                remoteId = "00000001test"
                permissions = "RDNVW"
                isFavorite = true
                isSharedViaLink = true
                isSharedWithSharee = true
                richWorkspace = "workspace"
                decryptedRemotePath = path
            }
        }
        val local = remoteRow(-1)
        storage.saveFile(local)
        storage.saveSynchronizedFolder(local, local, emptyList(), emptyList())
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
        storage.updateInternalSyncEnrollment(local, true)
        storage.updateInternalSyncResult(local, 126, "OK")
        val before = readRow(path)
        repeat(2) {
            val current = storage.getFileByPath(path)
            val remote = remoteRow(current.fileId)
            storage.saveSynchronizedFolder(remote, current, emptyList(), emptyList())
            assertEquals(before, readRow(path))
            assertEquals(4096L, storage.getFileByPath(path).fileLength)
        }
    }

    @Test
    fun staleSynchronizedFolderCannotOverwriteConcurrentLocalChanges() {
        val stale = storage.getFileByPath("/")
        root.isReadOnly = true
        root.lastSyncDateForData = 1234
        root.decryptedRemotePath = "/renamed/"
        storage.saveFile(root)
        val remote = directory("/").apply {
            fileId = root.fileId
            etag = "fresh-token"
        }
        storage.saveSynchronizedFolder(remote, stale, emptyList(), emptyList())
        val saved = storage.getFileByPath("/")
        assertTrue(saved.isReadOnly)
        assertEquals(1234L, saved.lastSyncDateForData)
        assertEquals("/renamed/", saved.decryptedRemotePath)
        assertEquals("fresh-token", saved.etagOnServer)
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
