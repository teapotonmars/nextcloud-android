/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import com.owncloud.android.datamodel.OCFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever

internal class SubtreeSkippingRetryTest : SubtreeSyncTest() {
    @Test
    fun browsingCachedRootEtagDoesNotHideDeepAddition() {
        warmUp()
        val added = ROOT + "a/deep/new"
        fixture.addRemote(added, false)
        changeAncestors("a/deep/")
        fixture.local.getValue(ROOT).etag = fixture.remote.getValue(ROOT).etag
        fixture.syncTree()
        assertEquals(listOf(added), fixture.downloads)
        assertEquals(3, fixture.listings.size)
    }

    @Test
    fun failedChangedChildIsRetriedAfterParentCertificateIsCommitted() {
        warmUp()
        val added = ROOT + "a/deep/new"
        fixture.addRemote(added, false)
        changeAncestors("a/deep/")
        fixture.failedListings.add(ROOT + "a/")
        fixture.syncTree()
        assertFalse(fixture.local.containsKey(added))
        fixture.failedListings.clear()
        fixture.resetCounts()
        fixture.syncTree()
        assertEquals(listOf(ROOT + "a/", ROOT + "a/deep/"), fixture.listings)
        assertEquals(listOf(added), fixture.downloads)
    }

    @Test
    fun cancellationAfterParentSaveLeavesUnvisitedChildrenDiscoverable() {
        val operation = SynchronizeFolderOperation(fixture.context, ROOT, fixture.user, fixture.storage, false, true)
        doAnswer {
            operation.cancel()
            null
        }.whenever(fixture.context).startService(any())
        assertFalse(operation.run(fixture.client).isSuccess)
        assertTrue(certificates.containsKey(ROOT))
        fixture.syncTree()
        assertEquals(30, fixture.downloads.size)
        assertTrue(fixture.local.values.filter { !it.isFolder }.all { it.isDown })
    }

    @Test
    fun failedCertificateReadFallsBackToFullDiscovery() {
        warmUp()
        whenever(fixture.storage.getFolderSyncSnapshot(any())).thenThrow(IllegalStateException("cache unavailable"))
        fixture.syncTree()
        assertEquals(7, fixture.listings.size)
        assertTrue(fixture.downloads.isEmpty())
    }

    @Test
    fun failedCertificateWriteDoesNotPreventContentsFromSyncing() {
        certificates.clear()
        doAnswer { throw IllegalStateException("cache unavailable") }
            .whenever(fixture.storage).saveFolderSyncSnapshot(any(), any())
        fixture.syncTree()
        assertEquals(30, fixture.downloads.size)
        fixture.resetCounts()
        fixture.syncTree()
        assertEquals(7, fixture.listings.size)
        assertTrue(fixture.downloads.isEmpty())
    }

    @Test
    fun failedInventoryWriteCannotCertifyAnUnstoredAddition() {
        warmUp()
        val added = ROOT + "a/deep/new"
        fixture.addRemote(added, false)
        changeAncestors("a/deep/")
        val original = certificates.getValue(ROOT + "a/deep/")
        var failWrites = true
        doAnswer {
            val folder = it.getArgument<OCFile>(0)
            if (folder.remotePath == ROOT + "a/deep/" && failWrites) return@doAnswer null
            for (file in it.getArgument<List<OCFile>>(1)) fixture.local[file.remotePath] = file
            fixture.local[folder.remotePath] = folder
            null
        }.whenever(fixture.storage).saveFolder(any(), any(), any())
        fixture.syncTree()
        assertFalse(fixture.local.containsKey(added))
        assertEquals(original, certificates.getValue(ROOT + "a/deep/"))
        failWrites = false
        fixture.resetCounts()
        fixture.syncTree()
        assertEquals(listOf(added), fixture.downloads)
    }

    @Test
    fun successfulModifiedDownloadRetainsReusableRemoteInventory() {
        warmUp()
        val modified = ROOT + "a/deep/file0"
        fixture.remote.getValue(modified).etag = "modified-content"
        fixture.remote.getValue(modified).modificationTimestamp += 1
        changeAncestors("a/deep/")
        fixture.syncTree()
        assertEquals(listOf(modified), fixture.downloads)
        assertEquals("modified-content", fixture.local.getValue(modified).etagOnServer)
        fixture.resetCounts()
        fixture.syncTree()
        assertTrue(fixture.listings.isEmpty())
        assertTrue(fixture.downloads.isEmpty())
    }

    companion object {
        private const val ROOT = FolderSyncFixture.ROOT
    }
}
