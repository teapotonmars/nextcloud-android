/*
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.datamodel

import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import com.nextcloud.client.account.User
import com.owncloud.android.MainApp
import com.owncloud.android.db.ProviderMeta.ProviderTableMeta
import com.owncloud.android.lib.common.utils.Log_OC
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

class FileConflictCleanupTest {
    private val resources = mutableListOf<AutoCloseable>()
    private lateinit var storage: FileDataStorageManager
    private lateinit var resolver: ContentResolver
    private lateinit var file: OCFile
    private val updates = mutableListOf<Pair<String, List<String>>>()
    private var persistedConflict: String? = null
    private var descendantConflicts = 0
    private var ancestorQueries = 0
    private var ancestorUpdates = 0
    private var queryAvailable = true

    @Before
    fun setUp() {
        resources.add(Mockito.mockStatic(Log_OC::class.java))
        resources.add(Mockito.mockStatic(MainApp::class.java))
        val uri = Mockito.mock(Uri::class.java)
        val uris = Mockito.mockStatic(Uri::class.java)
        uris.`when`<Uri> { Uri.parse(ArgumentMatchers.anyString()) }.thenReturn(uri)
        resources.add(uris)
        resources.add(Mockito.mockConstruction(ContentValues::class.java))
        resolver = Mockito.mock(ContentResolver::class.java)
        storage = Mockito.mock(FileDataStorageManager::class.java)
        Mockito.`when`(storage.contentResolver).thenReturn(resolver)
        val user = Mockito.mock(User::class.java)
        Mockito.`when`(user.accountName).thenReturn("test-account")
        FileDataStorageManager::class.java.getDeclaredField("user").apply {
            isAccessible = true
            set(storage, user)
        }
        file = Mockito.mock(OCFile::class.java)
        Mockito.`when`(file.fileId).thenReturn(42L)
        Mockito.`when`(file.remotePath).thenReturn("/root/deep/file")
        Mockito.`when`(file.isDown).thenReturn(true)
        Mockito.doCallRealMethod().`when`(storage).saveConflict(file, null)
        configureResolver()
    }

    private fun configureResolver() {
        Mockito.`when`(
            resolver.update(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any()
            )
        ).thenAnswer { call ->
            val selection = call.getArgument<String>(2)
            val arguments = call.getArgument<Array<String>>(3).toList()
            updates.add(selection to arguments)
            if (arguments.first() == "42") {
                assertTrue(selection.contains(ProviderTableMeta.FILE_ACCOUNT_OWNER + "=?"))
                assertEquals(listOf("42", "test-account"), arguments)
                if (!selection.endsWith(ProviderTableMeta.FILE_ETAG_IN_CONFLICT + " IS NOT NULL")) {
                    ancestorUpdates++
                    return@thenAnswer 1
                }
                if (persistedConflict == null) {
                    0
                } else {
                    persistedConflict = null
                    1
                }
            } else {
                ancestorUpdates++
                1
            }
        }
        Mockito.`when`(
            resolver.query(
                ArgumentMatchers.any(),
                ArgumentMatchers.any(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.any(),
                ArgumentMatchers.isNull<String>()
            )
        ).thenAnswer {
            ancestorQueries++
            if (!queryAvailable) {
                return@thenAnswer null
            }
            Mockito.mock(Cursor::class.java).also { cursor ->
                Mockito.`when`(cursor.count).thenReturn(descendantConflicts)
            }
        }
    }

    @After
    fun tearDown() {
        resources.reversed().forEach { it.close() }
    }

    @Test
    fun interruptedCleanupCanRepairAnOrphanedFolderMarkerOnRetry() {
        Mockito.doCallRealMethod().`when`(storage).clearFolderConflictIfResolved(file)
        storage.clearFolderConflictIfResolved(file)
        assertEquals(1, ancestorQueries)
        assertEquals(1, ancestorUpdates)
    }

    @Test
    fun uncertainFolderQueryRetainsTheMarkerForRetry() {
        queryAvailable = false
        Mockito.doCallRealMethod().`when`(storage).clearFolderConflictIfResolved(file)
        storage.clearFolderConflictIfResolved(file)
        assertEquals(1, ancestorQueries)
        assertEquals(0, ancestorUpdates)
    }

    @Test
    fun folderRepairRetainsMarkersForRealDescendantConflicts() {
        descendantConflicts = 1
        Mockito.doCallRealMethod().`when`(storage).clearFolderConflictIfResolved(file)
        storage.clearFolderConflictIfResolved(file)
        assertEquals(1, ancestorQueries)
        assertEquals(0, ancestorUpdates)
    }

    @Test
    fun unchangedFileWithoutStoredConflictDoesNotWalkAncestors() {
        repeat(30) { storage.saveConflict(file, null) }
        assertEquals(30, updates.size)
        assertEquals(0, ancestorQueries)
        assertEquals(0, ancestorUpdates)
    }

    @Test
    fun clearingStoredConflictStillRecalculatesEveryAncestor() {
        persistedConflict = "old-conflict"
        storage.saveConflict(file, null)
        assertEquals(3, ancestorQueries)
        assertEquals(3, ancestorUpdates)
        storage.saveConflict(file, null)
        assertEquals(3, ancestorQueries)
    }

    @Test
    fun otherDescendantConflictsPreventClearingAncestorMarkers() {
        persistedConflict = "resolved-conflict"
        descendantConflicts = 1
        storage.saveConflict(file, null)
        assertEquals(3, ancestorQueries)
        assertEquals(0, ancestorUpdates)
    }
}
