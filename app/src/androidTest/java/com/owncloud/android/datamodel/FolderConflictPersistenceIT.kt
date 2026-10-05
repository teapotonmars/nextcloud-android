/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.datamodel

import android.content.ContentValues
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
class FolderConflictPersistenceIT {
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
    fun missingDescendantMimeMetadataRetainsTheConflictMarker() {
        val folder = directory("/uncertain/").apply {
            parentId = root.fileId
            etagInConflict = "orphan"
        }
        storage.saveFile(folder)
        val inside = OCFile("/uncertain/file").apply {
            mimeType = "text/plain"
            parentId = folder.fileId
            etagInConflict = "unresolved"
        }
        storage.saveFile(inside)
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        resolver.update(
            ProviderTableMeta.CONTENT_URI_FILE,
            ContentValues().apply { putNull(ProviderTableMeta.FILE_CONTENT_TYPE) },
            "${ProviderTableMeta.FILE_ACCOUNT_OWNER}=? AND ${ProviderTableMeta.FILE_PATH}=?",
            arrayOf(account, inside.remotePath)
        )
        storage.clearFolderConflictIfResolved(folder)
        assertTrue(storage.getFileByPath(folder.remotePath).isInConflict)
    }

    @Test
    fun anotherAccountsConflictCannotBlockRepairOrBeCleared() {
        val folder = directory("/shared-path/").apply {
            parentId = root.fileId
            etagInConflict = "orphan"
        }
        storage.saveFile(folder)
        val otherUser = mock<User>()
        whenever(otherUser.accountName).thenReturn("$account-other")
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val otherStorage = FileDataStorageManager(otherUser, resolver)
        val otherRoot = directory("/")
        otherStorage.saveFile(otherRoot)
        try {
            val otherConflict = OCFile("/shared-path/conflict").apply {
                mimeType = "text/plain"
                parentId = otherRoot.fileId
                etagInConflict = "other-account-conflict"
            }
            otherStorage.saveFile(otherConflict)
            storage.clearFolderConflictIfResolved(folder)
            assertFalse(storage.getFileByPath(folder.remotePath).isInConflict)
            assertEquals("other-account-conflict", otherStorage.getFileByPath(otherConflict.remotePath).etagInConflict)
        } finally {
            otherStorage.removeFolder(otherRoot, true, false)
        }
    }

    @Test
    fun aRemovedFoldersRepairCannotClearItsReplacementsMarker() {
        val removed = directory("/replacement/").apply { parentId = root.fileId }
        storage.saveFile(removed)
        storage.removeFolder(removed, true, false)
        val replacement = directory(removed.remotePath).apply {
            parentId = root.fileId
            etagInConflict = "replacement-marker"
        }
        storage.saveFile(replacement)
        storage.clearFolderConflictIfResolved(removed)
        assertEquals("replacement-marker", storage.getFileByPath(replacement.remotePath).etagInConflict)
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val changed = java.util.concurrent.CountDownLatch(1)
        val observer = object : android.database.ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                changed.countDown()
            }
        }
        resolver.registerContentObserver(ProviderTableMeta.CONTENT_URI, true, observer)
        try {
            storage.clearFolderConflictIfResolved(replacement)
            assertTrue(changed.await(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(storage.getFileByPath(replacement.remotePath).isInConflict)
        } finally {
            resolver.unregisterContentObserver(observer)
        }
    }

    @Test
    fun concurrentConflictCreationCannotLoseItsFolderMarker() {
        val folder = directory("/concurrent/").apply { parentId = root.fileId }
        storage.saveFile(folder)
        val inside = OCFile("/concurrent/file").apply {
            mimeType = "text/plain"
            parentId = folder.fileId
            storagePath = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
                .resolve("folder-conflict-$account").apply { writeText("content") }.path
        }
        storage.saveFile(inside)
        folder.etagInConflict = "orphan"
        storage.saveFile(folder)
        storage.clearFolderConflictIfResolved(folder)
        assertFalse(storage.getFileByPath(folder.remotePath).isInConflict)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            repeat(REPETITIONS) {
                inside.etagInConflict = null
                storage.saveFile(inside)
                folder.etagInConflict = "orphan"
                storage.saveFile(folder)
                val start = java.util.concurrent.CountDownLatch(1)
                val repair = executor.submit {
                    assertTrue(start.await(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
                    storage.clearFolderConflictIfResolved(folder)
                }
                val conflict = executor.submit {
                    assertTrue(start.await(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS))
                    storage.saveConflict(inside, "new-conflict")
                }
                start.countDown()
                repair.get(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                conflict.get(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals("new-conflict", storage.getFileByPath(inside.remotePath).etagInConflict)
                assertTrue(storage.getFileByPath(folder.remotePath).isInConflict)
            }
        } finally {
            executor.shutdownNow()
            java.io.File(inside.storagePath).delete()
        }
    }

    private fun directory(path: String) = OCFile(path).apply { mimeType = "httpd/unix-directory" }

    companion object {
        private const val REPETITIONS = 20
        private const val TIMEOUT_SECONDS = 5L
    }
}
