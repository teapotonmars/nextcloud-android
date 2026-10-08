/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import com.owncloud.android.datamodel.FolderSyncSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

internal class ForcedFolderSyncTest : SubtreeSyncTest() {
    @Test
    fun manualSyncFindsDeepChangesWithUnchangedEtagsAndRecertifiesEveryDirectory() {
        warmUp()
        val added = ROOT + "a/deep/new"
        fixture.addRemote(added, false)
        fixture.syncTree()
        assertTrue(fixture.listings.isEmpty())
        assertFalse(fixture.local.containsKey(added))

        fixture.resetCounts()
        fixture.queuedModes[ROOT] = FolderSyncMode.RECURSIVE_FORCED
        fixture.syncTree()
        assertEquals(7, fixture.listings.size)
        assertTrue(fixture.checks.isEmpty())
        assertEquals(listOf(added), fixture.downloads)
        for ((path, certificate) in certificates) {
            val folder = fixture.local.getValue(path)
            val children = fixture.storage.getFolderContent(folder, false)
            assertTrue(FolderSyncSnapshot.decode(certificate)!!.matches(folder, children, System.currentTimeMillis()))
        }

        fixture.resetCounts()
        fixture.syncTree()
        assertEquals(listOf(ROOT), fixture.checks)
        assertTrue(fixture.listings.isEmpty())
        assertTrue(fixture.downloads.isEmpty())
    }

    @Test
    fun cachedSyncWaitsForExpiryToDiscoverChangesWithoutEtagPropagation() {
        warmUp()
        val added = ROOT + "a/deep/new"
        fixture.addRemote(added, false)
        fixture.syncTree()
        assertFalse(fixture.local.containsKey(added))
        assertTrue(fixture.listings.isEmpty())
        for ((path, certificate) in certificates.toMap()) {
            certificates[path] = FolderSyncSnapshot.decode(certificate)!!.copy(
                createdAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)
            ).encode()
        }
        fixture.resetCounts()
        fixture.syncTree()
        assertEquals(7, fixture.listings.size)
        assertEquals(listOf(added), fixture.downloads)
    }

    companion object {
        private const val ROOT = FolderSyncFixture.ROOT
    }
}
