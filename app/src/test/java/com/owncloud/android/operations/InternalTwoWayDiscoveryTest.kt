/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.nextcloud.client.account.UserAccountManager
import com.nextcloud.client.device.PowerManagementService
import com.nextcloud.client.jobs.InternalTwoWaySyncWork
import com.nextcloud.client.network.Connectivity
import com.nextcloud.client.network.ConnectivityService
import com.nextcloud.client.preferences.AppPreferences
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.nio.file.Files
import java.nio.file.Path

class InternalTwoWayDiscoveryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var fixture: FolderSyncFixture
    private val accounts = mock<UserAccountManager>()
    private val power = mock<PowerManagementService>()
    private val connectivity = mock<ConnectivityService>()
    private val preferences = mock<AppPreferences>()

    @Before
    fun setUp() {
        fixture = FolderSyncFixture(temporaryFolder)
        fixture.populate()
        fixture.local.getValue(ROOT).storagePath = temporaryFolder.root.resolve("not-downloaded").path
        whenever(accounts.allUsers).thenReturn(listOf(fixture.user))
        whenever(connectivity.isConnected).thenReturn(true)
        whenever(connectivity.connectivity).thenReturn(Connectivity.CONNECTED_WIFI)
        whenever(preferences.isTwoWaySyncEnabled).thenReturn(true)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun firstScheduledSyncDiscoversTheWholeUncachedTree() {
        runScheduledSync()

        assertEquals(30, fixture.downloads.size)
        assertEquals(7, fixture.listings.size)
        assertTrue(fixture.local.values.filter { !it.isFolder }.all { it.isDown })
    }

    @Test
    fun discoversAdditionWithUnchangedAncestorEtags() {
        warmUp()
        val added = ROOT + "a/deep/new"
        fixture.addRemote(added, false)

        runScheduledSync()

        assertEquals(listOf(added), fixture.downloads)
        assertEquals(7, fixture.listings.size)
        assertTrue(fixture.recursiveModes.all { it })
    }

    @Test
    fun discoversNewFolderChainBeyondCachedDepth() {
        warmUp()
        val parent = ROOT + "a/deep/"
        val chain = listOf("new/", "new/one/", "new/one/two/", "new/one/two/three/")
        chain.forEach { fixture.addRemote(parent + it, true) }
        val added = parent + chain.last() + "file"
        fixture.addRemote(added, false)

        runScheduledSync()

        assertEquals(listOf(added), fixture.downloads)
        assertEquals(11, fixture.listings.size)
        assertTrue(fixture.local.getValue(added).isDown)
    }

    @Test
    fun cachedAncestorMetadataDoesNotHidePendingDescendants() {
        warmUp()
        val added = ROOT + "a/deep/new"
        fixture.addRemote(added, false)
        fixture.remote.getValue(ROOT).etag = "already-refreshed"
        fixture.local.getValue(ROOT).etag = "already-refreshed"

        runScheduledSync()

        assertEquals(listOf(added), fixture.downloads)
    }

    @Test
    fun unchangedRepeatDoesNotDownloadExistingFiles() {
        warmUp()

        runScheduledSync()

        assertEquals(7, fixture.listings.size)
        assertEquals(30, fixture.fileChecks.size)
        assertTrue(fixture.downloads.isEmpty())
    }

    @Test
    fun failedNestedListingIsRetriedAfterAncestorMetadataWasSaved() {
        warmUp()
        val added = ROOT + "a/deep/new"
        fixture.addRemote(added, false)
        fixture.remote.getValue(ROOT).etag = "changed-root"
        fixture.failedListings.add(ROOT + "a/")

        runScheduledSync()
        assertFalse(fixture.local.containsKey(added))
        assertTrue(fixture.results.any { !it.isSuccess })
        fixture.failedListings.clear()
        fixture.resetCounts()

        runScheduledSync()

        assertEquals(listOf(added), fixture.downloads)
    }

    @Test
    fun failedNewFileDownloadIsRetried() {
        warmUp()
        val added = ROOT + "a/deep/new"
        fixture.addRemote(added, false)
        fixture.failedDownloads.add(added)

        runScheduledSync()
        assertFalse(fixture.local.getValue(added).isDown)
        fixture.failedDownloads.clear()
        fixture.resetCounts()

        runScheduledSync()

        assertEquals(listOf(added), fixture.downloads)
        assertTrue(fixture.local.getValue(added).isDown)
    }

    @Test
    fun restoresMissingNestedCopyWithUnchangedRemoteEtags() {
        warmUp()
        val missing = ROOT + "b/deep/file0"
        Files.delete(Path.of(fixture.local.getValue(missing).storagePath))

        runScheduledSync()

        assertEquals(listOf(missing), fixture.downloads)
    }

    @Test
    fun wifiAndPowerSavingConstraintsStillPreventWork() {
        whenever(power.isPowerSavingEnabled).thenReturn(true)
        assertTrue(scheduledModes().isEmpty())
        whenever(power.isPowerSavingEnabled).thenReturn(false)
        whenever(connectivity.connectivity).thenReturn(Connectivity.DISCONNECTED)
        assertTrue(scheduledModes().isEmpty())
        assertTrue(fixture.listings.isEmpty())
    }

    private fun warmUp() {
        fixture.syncTree()
        fixture.resetCounts()
    }

    private fun runScheduledSync() {
        val modes = scheduledModes()
        assertEquals(1, modes.size)
        fixture.syncTree(modes.single())
        println(
            "background: depth0=${fixture.checks.size}, depth1=${fixture.listings.size}, " +
                "fileChecks=${fixture.fileChecks.size}, downloads=${fixture.downloads.size}"
        )
    }

    private fun scheduledModes(): List<Boolean> {
        val modes = mutableListOf<Boolean>()
        val result = mock<RemoteOperationResult<Any>>()
        whenever(result.isSuccess).thenReturn(true)
        whenever(result.code).thenReturn(RemoteOperationResult.ResultCode.OK)
        mockConstruction(FileDataStorageManager::class.java) { storage, _ ->
            whenever(storage.getInternalTwoWaySyncFolders(fixture.user))
                .thenReturn(listOf(fixture.local.getValue(ROOT)))
            whenever(storage.getFileByPath(ROOT)).thenReturn(
                fixture.local.getValue(ROOT).apply {
                    internalFolderSyncTimestamp = 0L
                }
            )
        }.use {
            mockConstruction(SynchronizeFolderOperation::class.java) { operation, construction ->
                modes.add(construction.arguments()[5] as Boolean)
                whenever(operation.execute(fixture.context)).thenReturn(result)
            }.use {
                val worker = InternalTwoWaySyncWork(
                    fixture.context,
                    mock<WorkerParameters>(),
                    accounts,
                    power,
                    connectivity,
                    preferences
                )
                assertEquals(ListenableWorker.Result.success(), worker.doWork())
            }
        }
        return modes
    }

    companion object {
        private const val ROOT = FolderSyncFixture.ROOT
    }
}
