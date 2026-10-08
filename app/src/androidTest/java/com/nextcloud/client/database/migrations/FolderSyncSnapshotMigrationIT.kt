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
        val before = helper.createDatabase(DATABASE, 106).use { database ->
            database.execSQL(
                "INSERT INTO filelist (_id,path,file_owner,etag,path_decrypted,is_encrypted," +
                    "media_path,etag_in_conflict,internal_two_way_sync_timestamp,internal_two_way_sync_result) " +
                    "VALUES (1,'/library/','test-account','listed-token','/Readable/',1," +
                    "'/local/library','conflict',1234,'OK')"
            )
            database.query("SELECT * FROM filelist WHERE _id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)) }
            }
        }
        helper.runMigrationsAndValidate(DATABASE, 107, true).use { database ->
            database.query("SELECT * FROM filelist WHERE _id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                val after = cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)) }
                assertEquals(before, after.filterKeys { it != "folder_sync_snapshot" })
                assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow("folder_sync_snapshot")))
            }
        }
    }

    companion object {
        private const val DATABASE = "folder-sync-migration-test"
    }
}
