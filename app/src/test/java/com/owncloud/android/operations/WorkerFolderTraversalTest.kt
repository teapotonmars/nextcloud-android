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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test
    fun workerWaitingBehindAManualFolderOperationStopsBeforeListingOrDownloading() {
        val executor = Executors.newFixedThreadPool(2)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val waiting = CountDownLatch(1)
        val allowed = AtomicBoolean(true)
        val manual = executor.submit {
            FolderSyncExecutionLock.execute(fixture.user.accountName, { false }) {
                acquired.countDown()
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                RemoteOperationResult<Any>(RemoteOperationResult.ResultCode.OK)
            }
        }
        try {
            assertTrue(acquired.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            fixture.workerSyncAllowed = BooleanSupplier {
                waiting.countDown()
                allowed.get()
            }
            val stopped = executor.submit {
                check(waiting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                allowed.set(false)
            }
            fixture.syncTree()
            stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertEquals(RemoteOperationResult.ResultCode.CANCELLED, fixture.results.single().code)
            assertTrue(fixture.checks.isEmpty())
            assertTrue(fixture.listings.isEmpty())
            assertTrue(fixture.downloads.isEmpty())
        } finally {
            release.countDown()
            manual.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    companion object {
        private const val ROOT = FolderSyncFixture.ROOT
        private const val TIMEOUT_SECONDS = 5L
    }
}
