/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import com.owncloud.android.datamodel.FolderSyncSnapshot
import com.owncloud.android.datamodel.e2e.v2.decrypted.DecryptedFolderMetadataFile
import com.owncloud.android.lib.resources.shares.ShareeUser
import com.owncloud.android.lib.resources.shares.ShareType
import org.mockito.kotlin.mock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test

internal class SubtreePersistenceTest : SubtreeSyncTest() {
    @Test
    fun refreshedRootMetadataIsRetainedAndReusableAfterPermissionChanges() {
        warmUp()
        fixture.remote.getValue(ROOT).apply {
            permissions = "changed-permissions"
            remoteId = "current-remote-id"
            modificationTimestamp += 1
        }
        fixture.syncTree()
        val stored = fixture.local.getValue(ROOT)
        assertEquals("changed-permissions", stored.permissions)
        assertEquals("current-remote-id", stored.remoteId)
        assertEquals(fixture.remote.getValue(ROOT).modificationTimestamp, stored.modificationTimestamp)
        fixture.resetCounts()
        fixture.syncTree()
        assertEquals(listOf(ROOT), fixture.checks)
        assertTrue(fixture.listings.isEmpty())
    }

    @Test
    fun cachedPassAdvancesFolderSyncTimestamp() {
        warmUp()
        fixture.local.getValue(ROOT).lastSyncDateForData = 1
        fixture.syncTree()
        assertTrue(fixture.local.getValue(ROOT).lastSyncDateForData > 1)
        assertTrue(fixture.listings.isEmpty())
    }

    @Test
    fun pendingChildUsesCurrentDatabaseEtagInsteadOfEarlierParentHint() {
        warmUp()
        val added = ROOT + "a/deep/new"
        var changed = false
        fixture.beforeFolder = java.util.function.Consumer { path ->
            if (path == ROOT + "a/" && !changed) {
                fixture.addRemote(added, false)
                changeAncestors("a/deep/")
                fixture.local.getValue(ROOT + "a/").etagOnServer = fixture.remote.getValue(ROOT + "a/").etag
                changed = true
            }
        }
        fixture.syncTree()
        assertEquals(listOf(added), fixture.downloads)
        assertTrue(fixture.listings.contains(ROOT + "a/"))
    }

    @Test
    fun folderSaveDoesNotReplacePersistedListingEtag() {
        warmUp()
        val row = fixture.local.getValue(ROOT)
        val listed = row.etag
        val stale = com.owncloud.android.datamodel.OCFile(ROOT).apply {
            mimeType = row.mimeType
            etag = "unlisted-token"
        }
        fixture.storage.saveFile(stale)
        assertEquals(listed, fixture.local.getValue(ROOT).etag)
        assertNotNull(FolderSyncSnapshot.decode(certificates[ROOT]))
    }

    @Test
    fun fullListingRetainsRootAndNestedTwoWaySyncEnrollment() {
        fixture.local.getValue(ROOT).internalFolderSyncTimestamp = 123
        fixture.local.getValue(ROOT).internalFolderSyncResult = "old-result"
        fixture.syncTree()
        fixture.local.getValue(ROOT + "a/").internalFolderSyncTimestamp = 456
        certificates.clear()
        fixture.syncTree()
        assertEquals(123L, fixture.local.getValue(ROOT).internalFolderSyncTimestamp)
        assertEquals("old-result", fixture.local.getValue(ROOT).internalFolderSyncResult)
        assertEquals(456L, fixture.local.getValue(ROOT + "a/").internalFolderSyncTimestamp)
    }

    @Test
    fun nestedEncryptedFolderListingRetainsDecryptedPathAndLocalState() {
        warmUp()
        val path = ROOT + "a/deep/"
        val decrypted = ROOT + "Readable/Nested/"
        val retainedSharees = listOf(ShareeUser("alice", "Alice", ShareType.USER))
        fixture.local.getValue(ROOT + "a/").apply {
            decryptedRemotePath = ROOT + "Readable/"
            isEncrypted = true
        }
        fixture.local.getValue(path).apply {
            decryptedRemotePath = decrypted
            isEncrypted = true
            isReadOnly = true
            lastSyncDateForProperties = 123
            sharees = retainedSharees
        }
        fixture.encryptedMetadata[path] = mock<DecryptedFolderMetadataFile>()
        certificates.remove(path)
        fixture.syncTree(path, true)
        val stored = fixture.local.getValue(path)
        assertEquals(decrypted, stored.decryptedRemotePath)
        assertEquals("Nested", stored.fileName)
        assertTrue(stored.isEncrypted)
        assertTrue(stored.isReadOnly)
        assertEquals(123L, stored.lastSyncDateForProperties)
        assertEquals(retainedSharees, stored.sharees)
        assertEquals(listOf(path), fixture.listings)
        assertTrue(fixture.results.single().isSuccess)
        org.junit.Assert.assertNull(certificates[path])
    }

    @Test
    fun fullListingRetainsRemoteFolderSizesAcrossRepeatedPasses() {
        fixture.remote.getValue(ROOT).fileLength = 4096
        fixture.remote.getValue(ROOT + "a/").fileLength = 1024
        repeat(2) {
            certificates.clear()
            fixture.syncTree()
            assertEquals(4096L, fixture.local.getValue(ROOT).fileLength)
            assertEquals(1024L, fixture.local.getValue(ROOT + "a/").fileLength)
        }
    }

    companion object {
        private const val ROOT = FolderSyncFixture.ROOT
    }
}
