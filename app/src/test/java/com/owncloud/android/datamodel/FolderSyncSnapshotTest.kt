/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.datamodel

import com.owncloud.android.lib.common.network.WebdavEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.TimeUnit

class FolderSyncSnapshotTest {
    private val folder = directory("/root/").apply { fileId = 1 }
    private val child = OCFile("/root/file").apply {
        mimeType = "text/plain"
        etagOnServer = "remote-etag"
    }
    private val children = listOf(child)
    private val snapshot = FolderSyncSnapshot("directory-etag", FolderSyncSnapshot.fingerprint(folder, children), 1)

    @Test
    fun serializationRoundTripAndMalformedRecords() {
        assertEquals(snapshot, FolderSyncSnapshot.decode(snapshot.encode()))
        for (value in listOf(null, "", "broken", "{}", "{\"etag\":\"x\"}")) {
            assertNull(FolderSyncSnapshot.decode(value))
        }
    }

    @Test
    fun expiryAndClockRollbackRequireFreshDiscovery() {
        assertTrue(snapshot.matches(folder, children, 1))
        assertFalse(snapshot.matches(folder, children, 0))
        assertFalse(snapshot.matches(folder, children, TimeUnit.HOURS.toMillis(24) + 1))
    }

    @Test
    fun invalidCertificateTimestampsCannotBypassExpiryThroughOverflow() {
        val invalid = snapshot.copy(createdAt = Long.MIN_VALUE)
        assertNull(FolderSyncSnapshot.decode(invalid.encode()))
        assertFalse(invalid.matches(folder, children, System.currentTimeMillis()))
        assertNull(FolderSyncSnapshot.decode(snapshot.copy(createdAt = 0).encode()))
    }

    @Test
    fun localContentStateDoesNotInvalidateRemoteInventory() {
        child.etag = "old-downloaded-etag"
        child.setStoragePath("/missing")
        child.lastSyncDateForData = 100
        child.etagInConflict = "conflict"
        assertTrue(snapshot.matches(folder, children, 2))
    }

    @Test
    fun missingOrChangedCachedRemoteMetadataInvalidatesInventory() {
        assertFalse(snapshot.matches(folder, emptyList(), 2))
        child.etagOnServer = "different"
        assertFalse(snapshot.matches(folder, children, 2))
    }

    @Test
    fun directoryIdentityPreventsReusingCertificateAfterRecreation() {
        folder.fileId = 2
        assertFalse(snapshot.matches(folder, children, 2))
    }

    @Test
    fun inventoryHashIsIndependentOfDatabaseRowOrdering() {
        val second = OCFile("/root/second").apply { etagOnServer = "second" }
        assertEquals(
            FolderSyncSnapshot.fingerprint(folder, listOf(child, second)),
            FolderSyncSnapshot.fingerprint(folder, listOf(second, child))
        )
    }

    @Test
    fun missingRemoteFileEtagsAndUnsupportedFoldersRejectSkipping() {
        child.etagOnServer = ""
        assertFalse(FolderSyncSnapshot.eligible(folder, children))
        assertFalse(FolderSyncSnapshot.eligible(folder.apply { isEncrypted = true }, emptyList()))
        folder.isEncrypted = false
        folder.mountType = WebdavEntry.MountType.EXTERNAL
        assertFalse(FolderSyncSnapshot.eligible(folder, emptyList()))
    }

    @Test
    fun mountedAncestorAndIncompleteAncestryRejectSkipping() {
        val storage = mock<FileDataStorageManager>()
        val root = directory("/")
        whenever(storage.getFileByPath("/")).thenReturn(root)
        assertTrue(FolderSyncSnapshot.supportsSkipping(folder, storage))
        root.mountType = WebdavEntry.MountType.EXTERNAL
        assertFalse(FolderSyncSnapshot.supportsSkipping(folder, storage))
        whenever(storage.getFileByPath("/")).thenReturn(null)
        assertFalse(FolderSyncSnapshot.supportsSkipping(folder, storage))
    }

    private fun directory(path: String) = OCFile(path).apply { mimeType = "httpd/unix-directory" }
}
