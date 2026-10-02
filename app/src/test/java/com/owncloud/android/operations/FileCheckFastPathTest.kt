/*
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

class FileCheckFastPathTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private lateinit var fixture: FolderSyncFixture

    @Before
    fun setUp() {
        fixture = FolderSyncFixture(temporaryFolder)
        fixture.populate()
        fixture.syncTree()
        fixture.resetCounts()
        Mockito.clearInvocations(fixture.storage)
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun unchangedFilesHaveNoConflictWritesOrProgressNotifications() {
        fixture.syncTree()
        assertEquals(30, fixture.fileChecks.size)
        assertEquals(0, fixture.downloads.size)
        assertEquals(0, fixture.notificationUpdates)
        Mockito.verify(fixture.storage, Mockito.never()).saveConflict(
            ArgumentMatchers.any(),
            ArgumentMatchers.isNull()
        )
    }

    @Test
    fun previouslyConflictedUnchangedFileStillClearsItsConflict() {
        val file = fixture.local.getValue(FolderSyncFixture.ROOT + "a/deep/file0")
        file.etagInConflict = "previous-server-content"
        fixture.syncTree()
        Mockito.verify(fixture.storage).saveConflict(file, null)
        assertEquals(0, fixture.downloads.size)
        assertEquals(0, fixture.notificationUpdates)
    }
}
