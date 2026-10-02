/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import com.owncloud.android.datamodel.FolderSyncSnapshot
import com.owncloud.android.lib.common.network.WebdavEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

internal class SubtreeSkippingTest : SubtreeSyncTest() {
    @Test
    fun unchangedRepeatUsesOneRootCheckAndNoListingsOrDownloads() {
        warmUp()
        fixture.syncTree()
        assertEquals(listOf(ROOT), fixture.checks)
        assertTrue(fixture.listings.isEmpty())
        assertTrue(fixture.downloads.isEmpty())
        assertEquals(30, fixture.fileChecks.size)
        assertEquals(0, fixture.folderRowsWritten)
        org.mockito.kotlin.verify(fixture.storage, org.mockito.kotlin.never()).getFileById(any())
    }

    @Test
    fun deeplyNestedAdditionListsOnlyChangedBranches() {
        warmUp()
        val added = ROOT + "a/deep/new"
        fixture.addRemote(added, false)
        changeAncestors("a/deep/")
        fixture.syncTree()
        assertEquals(listOf(ROOT, ROOT + "a/", ROOT + "a/deep/"), fixture.listings)
        assertEquals(listOf(ROOT), fixture.checks)
        assertEquals(listOf(added), fixture.downloads)
        assertEquals(31, fixture.fileChecks.size)
    }

    @Test
    fun entirelyNewNestedDirectoriesAreDiscovered() {
        warmUp()
        val branch = ROOT + "a/deep/new/"
        fixture.addRemote(branch, true)
        fixture.addRemote(branch + "nested/", true)
        fixture.addRemote(branch + "nested/file", false)
        changeAncestors("a/deep/")
        fixture.syncTree()
        assertEquals(listOf(branch + "nested/file"), fixture.downloads)
        assertEquals(5, fixture.listings.size)
    }

    @Test
    fun missingNestedLocalCopyIsRestoredWithoutRemoteListings() {
        warmUp()
        val missing = ROOT + "b/deep/file0"
        Files.delete(Path.of(fixture.local.getValue(missing).storagePath))
        fixture.syncTree()
        assertEquals(listOf(missing), fixture.downloads)
        assertTrue(fixture.listings.isEmpty())
    }

    @Test
    fun failedInitialChildListingDoesNotMakeParentHideItOnRetry() {
        fixture.failedListings.add(ROOT + "a/")
        fixture.syncTree()
        fixture.failedListings.clear()
        fixture.resetCounts()
        fixture.syncTree()
        assertEquals(listOf(ROOT + "a/", ROOT + "a/deep/"), fixture.listings)
        assertEquals(10, fixture.downloads.size)
    }

    @Test
    fun failedDownloadIsRetriedUsingCertifiedRemoteMetadata() {
        warmUp()
        val modified = ROOT + "a/deep/file0"
        fixture.remote.getValue(modified).etag = "modified-content"
        changeAncestors("a/deep/")
        fixture.failedDownloads.add(modified)
        fixture.syncTree()
        assertNotEquals("modified-content", fixture.local.getValue(modified).etag)
        fixture.failedDownloads.clear()
        fixture.resetCounts()
        fixture.syncTree()
        assertTrue(fixture.listings.isEmpty())
        assertEquals(listOf(modified), fixture.downloads)
        assertEquals("modified-content", fixture.local.getValue(modified).etag)
    }

    @Test
    fun remoteDeletionIsReconciledWithoutScanningSiblingBranches() {
        warmUp()
        val removed = ROOT + "a/deep/file0"
        fixture.remote.remove(removed)
        changeAncestors("a/deep/")
        fixture.syncTree()
        assertFalse(fixture.local.containsKey(removed))
        assertEquals(3, fixture.listings.size)
        assertTrue(fixture.downloads.isEmpty())
    }

    @Test
    fun missingCachedEntryInvalidatesItsDirectoryCertificate() {
        warmUp()
        val missing = ROOT + "b/deep/file0"
        fixture.local.remove(missing)
        fixture.syncTree()
        assertEquals(listOf(ROOT + "b/deep/"), fixture.listings)
        assertEquals(listOf(missing), fixture.downloads)
    }

    @Test
    fun missingChildCertificateForcesDiscoveryDespiteCachedAncestorEtags() {
        warmUp()
        certificates.remove(ROOT + "a/deep/")
        val added = ROOT + "a/deep/new"
        fixture.addRemote(added, false)
        fixture.syncTree()
        assertEquals(listOf(ROOT + "a/deep/"), fixture.listings)
        assertEquals(listOf(added), fixture.downloads)
    }

    @Test
    fun localEditsAreUploadedWhileRemoteDiscoveryIsSkipped() {
        warmUp()
        val changed = ROOT + "a/deep/file0"
        Files.setLastModifiedTime(
            Path.of(fixture.local.getValue(changed).storagePath),
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000)
        )
        fixture.syncTree()
        assertEquals(listOf(changed), fixture.uploads)
        assertTrue(fixture.listings.isEmpty())
    }

    @Test
    fun outstandingConflictIsStillDetectedUsingCertifiedMetadata() {
        warmUp()
        val changed = ROOT + "a/deep/file0"
        fixture.remote.getValue(changed).etag = "remote-edit"
        changeAncestors("a/deep/")
        Files.setLastModifiedTime(
            Path.of(fixture.local.getValue(changed).storagePath),
            java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 60_000)
        )
        fixture.syncTree()
        fixture.resetCounts()
        fixture.syncTree()
        assertTrue(fixture.listings.isEmpty())
        assertTrue(fixture.downloads.isEmpty())
        assertTrue(fixture.uploads.isEmpty())
        org.mockito.Mockito.verify(fixture.storage, org.mockito.Mockito.atLeast(2))
            .saveConflict(fixture.local.getValue(changed), "remote-edit")
    }

    @Test
    fun expiredCertificatesRediscoverChangesWithUnpropagatedEtags() {
        warmUp()
        for ((path, encoded) in certificates.toMap()) {
            certificates[path] = FolderSyncSnapshot.decode(encoded)!!.copy(createdAt = 0).encode()
        }
        val added = ROOT + "a/deep/new"
        fixture.addRemote(added, false)
        fixture.syncTree()
        assertEquals(7, fixture.listings.size)
        assertEquals(listOf(added), fixture.downloads)
    }

    @Test
    fun encryptedAndExternalDirectoriesRejectCertificates() {
        warmUp()
        fixture.remote.getValue(ROOT + "a/").mountType = WebdavEntry.MountType.EXTERNAL
        fixture.local.getValue(ROOT + "a/").mountType = WebdavEntry.MountType.EXTERNAL
        fixture.remote.getValue(ROOT + "b/").isEncrypted = true
        fixture.local.getValue(ROOT + "b/").isEncrypted = true
        fixture.syncTree()
        assertTrue(fixture.listings.contains(ROOT + "a/"))
        assertTrue(fixture.listings.contains(ROOT + "b/"))
    }

    @Test
    fun concurrentAdditionAfterListingIsDiscoveredOnNextRun() {
        warmUp()
        val first = ROOT + "a/deep/first"
        val second = ROOT + "a/deep/second"
        fixture.addRemote(first, false)
        changeAncestors("a/deep/")
        fixture.afterListing = Runnable {
            if (fixture.listings.last() == ROOT + "a/deep/" && !fixture.remote.containsKey(second)) {
                fixture.addRemote(second, false)
                changeAncestors("a/deep/")
            }
        }
        fixture.syncTree()
        assertFalse(fixture.local.containsKey(second))
        fixture.resetCounts()
        fixture.syncTree()
        assertEquals(listOf(second), fixture.downloads)
    }

    companion object {
        private const val ROOT = FolderSyncFixture.ROOT
    }
}
