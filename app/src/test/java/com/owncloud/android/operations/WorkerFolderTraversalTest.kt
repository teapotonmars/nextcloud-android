/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import com.owncloud.android.lib.common.operations.RemoteOperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.function.BooleanSupplier

internal class WorkerFolderTraversalTest : SubtreeSyncTest() {
    @Test
    fun workerTraversalDiscoversDescendantsWithoutStartingServices() {
        fixture.workerSyncAllowed = BooleanSupplier { true }
        fixture.syncTree()
        assertEquals(30, fixture.downloads.size)
        assertEquals(7, fixture.listings.size)
        assertTrue(fixture.recursiveModes.isEmpty())
    }

    @Test
    fun workerTraversalStopsBeforeFurtherDownloadsWhenConstraintsChange() {
        fixture.workerSyncAllowed = BooleanSupplier { fixture.downloads.isEmpty() }
        fixture.syncTree()
        assertEquals(1, fixture.downloads.size)
        assertTrue(fixture.recursiveModes.isEmpty())
        assertTrue(fixture.results.any { it.code == RemoteOperationResult.ResultCode.CANCELLED })
    }

    @Test
    fun workerTraversalReusesCertificatesFromFreshListings() {
        fixture.queuedModes[ROOT] = FolderSyncMode.RECURSIVE_FORCED
        fixture.syncTree()
        fixture.resetCounts()
        fixture.workerSyncAllowed = BooleanSupplier { true }
        fixture.syncTree()
        assertEquals(listOf(ROOT), fixture.checks)
        assertTrue(fixture.listings.isEmpty())
        assertTrue(fixture.downloads.isEmpty())
        assertTrue(fixture.recursiveModes.isEmpty())
    }

    companion object {
        private const val ROOT = FolderSyncFixture.ROOT
    }
}
