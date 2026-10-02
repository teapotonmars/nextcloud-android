/*
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
