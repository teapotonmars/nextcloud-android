/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.nextcloud.client.database.migrations

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.nextcloud.client.database.NextcloudDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FolderSyncSnapshotMigrationIT {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NextcloudDatabase::class.java)

    @Test
    fun existingFolderMetadataSurvivesAndStartsWithoutCertificate() {
        helper.createDatabase(DATABASE, 106).apply {
            execSQL(
                "INSERT INTO filelist (_id,path,file_owner,etag) VALUES (1,'/library/','test-account','listed-token')"
            )
            close()
        }
        helper.runMigrationsAndValidate(DATABASE, 107, true).use { database ->
            database.query("SELECT path,etag,folder_sync_snapshot FROM filelist WHERE _id=1").use {
                assertTrue(it.moveToFirst())
                assertEquals("/library/", it.getString(0))
                assertEquals("listed-token", it.getString(1))
                assertTrue(it.isNull(2))
            }
        }
    }

    companion object {
        private const val DATABASE = "folder-sync-migration-test"
    }
}
