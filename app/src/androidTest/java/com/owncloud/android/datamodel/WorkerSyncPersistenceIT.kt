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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkerSyncPersistenceIT {
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
    fun targetedFolderWritesNotifyContentObservers() {
        storage.updateInternalSyncEnrollment(root, true)
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        for (write in listOf<() -> Unit>(
            { storage.updateInternalSyncResult(root, 1235, "OK") }
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
    fun workerResultUpdateCannotOverwriteFreshListingMetadata() {
        val stale = storage.getFileByPath("/")
        root.apply {
            internalFolderSyncTimestamp = 0
            etagOnServer = "new-parent-token"
            permissions = "new-permissions"
            remoteId = "00000002test"
        }
        storage.saveFile(root)
        storage.updateInternalSyncEnrollment(root, true)
        storage.saveFolder(root, emptyList(), emptyList())
        val before = readRow("/")
        storage.updateInternalSyncResult(stale, 1234, "OK")
        val after = readRow("/")
        val expected = before.toMutableMap().apply {
            put(ProviderTableMeta.FILE_INTERNAL_TWO_WAY_SYNC_TIMESTAMP, "1234")
            put(ProviderTableMeta.FILE_INTERNAL_TWO_WAY_SYNC_RESULT, "OK")
        }
        assertEquals(expected, after)
        storage.updateInternalSyncResult(stale, 1235, null)
        assertEquals("OK", storage.getFileByPath("/").internalFolderSyncResult)
    }

    @Test
    fun staleListingsCannotOverwriteWorkerResultsOrEnrollmentChanges() {
        val child = directory("/child/")
        storage.saveFolder(root, listOf(child), emptyList())
        val staleRoot = storage.getFileByPath("/")
        val staleChild = storage.getFileByPath("/child/")
        for (timestamp in listOf(1234L, -1L)) {
            for (path in listOf("/", "/child/")) {
                val current = storage.getFileByPath(path).apply {
                    internalFolderSyncTimestamp = timestamp
                    internalFolderSyncResult = "new-result"
                }
                storage.updateInternalSyncEnrollment(current, true)
                storage.updateInternalSyncResult(current, 1234, "new-result")
                if (timestamp < 0) storage.updateInternalSyncEnrollment(current, false)
            }
            storage.saveFolder(staleRoot, listOf(staleChild), emptyList())
            storage.saveFolder(staleChild, emptyList(), emptyList())
            storage.saveFile(staleRoot)
            storage.saveFile(staleChild)
            for (path in listOf("/", "/child/")) {
                val saved = storage.getFileByPath(path)
                assertEquals(timestamp, saved.internalFolderSyncTimestamp)
                assertEquals("new-result", saved.internalFolderSyncResult)
            }
            if (timestamp < 0) {
                storage.updateInternalSyncResult(staleRoot, 9999, "late-result")
                storage.updateInternalSyncResult(staleChild, 9999, "late-result")
                assertEquals(-1L, storage.getFileByPath("/").internalFolderSyncTimestamp)
                assertEquals(-1L, storage.getFileByPath("/child/").internalFolderSyncTimestamp)
                assertEquals("new-result", storage.getFileByPath("/").internalFolderSyncResult)
                assertEquals("new-result", storage.getFileByPath("/child/").internalFolderSyncResult)
            }
        }
    }

    @Test
    fun workerResultCannotUpdateAnotherAccountOrARecreatedFolder() {
        val otherUser = mock<User>()
        whenever(otherUser.accountName).thenReturn("$account-other")
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val otherStorage = FileDataStorageManager(otherUser, resolver)
        val otherRoot = directory("/").apply { internalFolderSyncTimestamp = 0 }
        otherStorage.saveFile(otherRoot)
        try {
            val wrongAccount = directory("/").apply { fileId = otherRoot.fileId }
            storage.updateInternalSyncResult(wrongAccount, 9999, "wrong-account")
            assertEquals(0L, otherStorage.getFileByPath("/").internalFolderSyncTimestamp)
            assertEquals("", otherStorage.getFileByPath("/").internalFolderSyncResult)
        } finally {
            otherStorage.removeFolder(otherRoot, true, false)
        }
        val removed = root
        storage.removeFolder(removed, true, false)
        root = directory("/").apply { internalFolderSyncTimestamp = 0 }
        storage.saveFile(root)
        storage.updateInternalSyncResult(removed, 9999, "removed-folder")
        assertEquals(0L, storage.getFileByPath("/").internalFolderSyncTimestamp)
        assertEquals("", storage.getFileByPath("/").internalFolderSyncResult)
    }

    @Test
    fun enrollmentUpdateCannotOverwriteFreshListingMetadata() {
        val stale = storage.getFileByPath("/")
        root.etagOnServer = "fresh-server-token"
        root.permissions = "fresh-permissions"
        storage.saveFile(root)
        val before = readRow("/")
        storage.updateInternalSyncEnrollment(stale, true)
        assertEquals(before + (ProviderTableMeta.FILE_INTERNAL_TWO_WAY_SYNC_TIMESTAMP to "0"), readRow("/"))
        storage.updateInternalSyncResult(stale, 1234, "OK")
        val afterResult = readRow("/")
        storage.updateInternalSyncEnrollment(stale, false)
        assertEquals(afterResult + (ProviderTableMeta.FILE_INTERNAL_TWO_WAY_SYNC_TIMESTAMP to "-1"), readRow("/"))
    }

    @Test
    fun cancelledResultPreservesThePersistedTimestampEvenWhenTheCallerIsStale() {
        storage.updateInternalSyncEnrollment(root, true)
        val stale = storage.getFileByPath("/")
        storage.updateInternalSyncResult(root, 1234, "OK")
        val before = readRow("/")
        storage.updateInternalSyncResult(stale, null, "CANCELLED")
        assertEquals(before + (ProviderTableMeta.FILE_INTERNAL_TWO_WAY_SYNC_RESULT to "CANCELLED"), readRow("/"))
        storage.updateInternalSyncEnrollment(root, false)
        storage.updateInternalSyncResult(stale, null, "OK")
        assertEquals(-1L, storage.getFileByPath("/").internalFolderSyncTimestamp)
        assertEquals("CANCELLED", storage.getFileByPath("/").internalFolderSyncResult)
    }

    @Test
    fun scalarEnrollmentCheckRejectsDisabledReplacedAndOtherAccountFolders() {
        assertFalse(storage.isInternalSyncEnrolled(root))
        storage.updateInternalSyncEnrollment(root, true)
        assertTrue(storage.isInternalSyncEnrolled(root))
        val wrongPath = directory("/other/").apply { fileId = root.fileId }
        assertFalse(storage.isInternalSyncEnrolled(wrongPath))
        val otherUser = mock<User>()
        whenever(otherUser.accountName).thenReturn("$account-other")
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        assertFalse(FileDataStorageManager(otherUser, resolver).isInternalSyncEnrolled(root))
        storage.updateInternalSyncEnrollment(root, false)
        assertFalse(storage.isInternalSyncEnrolled(root))
        val removed = root
        storage.removeFolder(removed, true, false)
        root = directory("/")
        storage.saveFile(root)
        storage.updateInternalSyncEnrollment(root, true)
        assertFalse(storage.isInternalSyncEnrolled(removed))
        assertTrue(storage.isInternalSyncEnrolled(root))
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
