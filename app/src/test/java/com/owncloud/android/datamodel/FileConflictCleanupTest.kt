/*
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.datamodel

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import com.nextcloud.client.account.User
import com.nextcloud.client.database.dao.FileDao
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
import org.mockito.kotlin.eq

class FileConflictCleanupTest {
    private val resources = mutableListOf<AutoCloseable>()
    private lateinit var storage: FileDataStorageManager
    private lateinit var resolver: ContentResolver
    private lateinit var dao: FileDao
    private lateinit var file: OCFile
    private val updates = mutableListOf<Pair<String, List<String>>>()
    private var persistedConflict: String? = null
    private var descendantConflicts = 0
    private var ancestorAttempts = 0
    private var ancestorUpdates = 0
    private var updateAvailable = true

    @Before
    fun setUp() {
        resources.add(Mockito.mockStatic(Log_OC::class.java))
        resources.add(Mockito.mockStatic(MainApp::class.java))
        resources.add(Mockito.mockStatic(ContentUris::class.java))
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
        dao = Mockito.mock(FileDao::class.java)
        FileDataStorageManager::class.java.getDeclaredField("fileDao").apply {
            isAccessible = true
            set(storage, dao)
        }
        Mockito.`when`(storage.getFileByPath(ArgumentMatchers.anyString())).thenAnswer {
            folder(it.getArgument(0))
        }
        Mockito.`when`(
            dao.clearFolderConflictIfResolved(
                eq("test-account"),
                ArgumentMatchers.anyLong(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString(),
                ArgumentMatchers.anyString()
            )
        ).thenAnswer {
            ancestorAttempts++
            if (!updateAvailable) throw IllegalStateException("database unavailable")
            if (descendantConflicts != 0) return@thenAnswer 0
            ancestorUpdates++
            1
        }
        file = Mockito.mock(OCFile::class.java)
        Mockito.`when`(file.fileId).thenReturn(42L)
        Mockito.`when`(file.remotePath).thenReturn("/root/deep/file")
        Mockito.`when`(file.isDown).thenReturn(true)
        Mockito.doCallRealMethod().`when`(storage).saveConflict(file, null)
        Mockito.doCallRealMethod().`when`(storage).clearFolderConflictIfResolved(ArgumentMatchers.any())
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
    }

    private fun folder(path: String): OCFile = Mockito.mock(OCFile::class.java).also {
        Mockito.`when`(it.remotePath).thenReturn(path)
        Mockito.`when`(it.fileId).thenReturn(77L)
    }

    private fun repairFolder() {
        val folder = folder("/root/deep/")
        storage.clearFolderConflictIfResolved(folder)
    }

    @After
    fun tearDown() {
        resources.reversed().forEach { it.close() }
    }

    @Test
    fun conflictSettingToleratesAnAncestorDeletedDuringTraversal() {
        Mockito.`when`(file.parentId).thenReturn(77L)
        Mockito.doCallRealMethod().`when`(storage).saveConflict(file, "new-conflict")
        storage.saveConflict(file, "new-conflict")
        assertEquals(1, updates.size)
        assertEquals(listOf("42", "test-account"), updates.single().second)
    }

    @Test
    fun malformedAncestorCycleTerminatesWithoutUpdatingAnotherAccount() {
        Mockito.`when`(file.parentId).thenReturn(77L)
        val parent = Mockito.mock(OCFile::class.java)
        Mockito.`when`(parent.parentId).thenReturn(77L)
        Mockito.`when`(storage.getFileById(77L)).thenReturn(parent)
        Mockito.doCallRealMethod().`when`(storage).saveConflict(file, "new-conflict")
        storage.saveConflict(file, "new-conflict")
        assertEquals(2, updates.size)
        assertEquals(listOf("77", "test-account"), updates.last().second)
    }

    @Test
    fun interruptedCleanupCanRepairAnOrphanedFolderMarkerOnRetry() {
        repairFolder()
        assertEquals(1, ancestorAttempts)
        assertEquals(1, ancestorUpdates)
    }

    @Test
    fun failedFolderUpdateRetainsTheMarkerForRetry() {
        updateAvailable = false
        repairFolder()
        assertEquals(1, ancestorAttempts)
        assertEquals(0, ancestorUpdates)
    }

    @Test
    fun folderRepairRetainsMarkersForRealDescendantConflicts() {
        descendantConflicts = 1
        repairFolder()
        assertEquals(1, ancestorAttempts)
        assertEquals(0, ancestorUpdates)
    }

    @Test
    fun unchangedFileWithoutStoredConflictDoesNotWalkAncestors() {
        repeat(30) { storage.saveConflict(file, null) }
        assertEquals(30, updates.size)
        assertEquals(0, ancestorAttempts)
        assertEquals(0, ancestorUpdates)
    }

    @Test
    fun clearingStoredConflictStillRecalculatesEveryAncestor() {
        persistedConflict = "old-conflict"
        storage.saveConflict(file, null)
        assertEquals(3, ancestorAttempts)
        assertEquals(3, ancestorUpdates)
        storage.saveConflict(file, null)
        assertEquals(3, ancestorAttempts)
    }

    @Test
    fun otherDescendantConflictsPreventClearingAncestorMarkers() {
        persistedConflict = "resolved-conflict"
        descendantConflicts = 1
        storage.saveConflict(file, null)
        assertEquals(3, ancestorAttempts)
        assertEquals(0, ancestorUpdates)
    }
}
