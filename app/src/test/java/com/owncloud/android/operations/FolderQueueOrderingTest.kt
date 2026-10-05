/*
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow

class FolderQueueOrderingTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var fixture: FolderSyncFixture

    @Before
    fun setUp() {
        fixture = FolderSyncFixture(temporaryFolder)
        fixture.populate()
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun initialDiscoveryCommitsEveryChildBeforeQueuingIt() {
        fixture.syncTree()

        assertEquals(30, fixture.downloads.size)
        assertTrue(fixture.results.all { it.isSuccess })
    }

    @Test
    fun failedPersistenceCannotStartAnyChild() {
        doThrow(IllegalStateException("database unavailable")).`when`(fixture.storage)
            .saveSynchronizedFolder(any(), any(), any(), any())
        assertThrows(IllegalStateException::class.java) { fixture.syncTree() }
        assertTrue(fixture.recursiveModes.isEmpty())
        assertTrue(fixture.downloads.isEmpty())
    }

    @Test
    fun cancellationDuringListingDoesNotQueueChildrenOrReplaceLocalMetadata() {
        val originalEtag = fixture.local.getValue(FolderSyncFixture.ROOT).etag
        val operation = SynchronizeFolderOperation(
            fixture.context,
            FolderSyncFixture.ROOT,
            fixture.user,
            fixture.storage,
            false,
            true
        )
        fixture.afterListing = Runnable { operation.cancel() }
        assertFalse(operation.run(fixture.client).isSuccess)
        assertTrue(fixture.recursiveModes.isEmpty())
        assertEquals(originalEtag, fixture.local.getValue(FolderSyncFixture.ROOT).etag)
    }

    @Test
    fun entirelyNewNestedBranchCommitsEachLevelBeforeQueuingIt() {
        fixture.syncTree()
        fixture.resetCounts()
        val branch = FolderSyncFixture.ROOT + "new/"
        fixture.addRemote(branch, true)
        fixture.addRemote(branch + "nested/", true)
        fixture.addRemote(branch + "nested/file", false)

        fixture.syncTree()

        assertEquals(listOf(branch + "nested/file"), fixture.downloads)
        assertTrue(fixture.results.all { it.isSuccess })
    }
}
