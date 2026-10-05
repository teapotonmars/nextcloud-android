/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever

internal abstract class SubtreeSyncTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    protected lateinit var fixture: FolderSyncFixture
    protected val certificates = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        fixture = FolderSyncFixture(temporaryFolder)
        fixture.populate()
        whenever(fixture.storage.getFolderSyncSnapshot(any())).thenAnswer { certificates[it.getArgument(0)] }
        doAnswer {
            certificates[it.getArgument(0)] = it.getArgument(1)
            null
        }.whenever(fixture.storage).saveFolderSyncSnapshot(any(), any())
    }

    @After
    fun tearDown() {
        fixture.close()
    }

    protected fun warmUp() {
        fixture.syncTree()
        fixture.resetCounts()
    }

    protected fun changeAncestors(relative: String) {
        var path = ROOT + relative
        while (path.startsWith(ROOT)) {
            fixture.remote.getValue(path).etag = "changed-${fixture.remote.size}-$path"
            if (path == ROOT) break
            path = path.trimEnd('/').substringBeforeLast('/') + "/"
        }
    }

    companion object {
        private const val ROOT = FolderSyncFixture.ROOT
    }
}
