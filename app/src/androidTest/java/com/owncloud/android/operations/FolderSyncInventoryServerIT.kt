/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import com.owncloud.android.AbstractOnServerIT
import org.junit.Test

class FolderSyncInventoryServerIT : AbstractOnServerIT() {
    @Test
    fun certifySkipAndDiscoverDeepAdditionUsingPersistedDatabaseRows() {
        FolderSyncServerScenario(targetContext, user, client, storageManager).run("/")
    }
}
