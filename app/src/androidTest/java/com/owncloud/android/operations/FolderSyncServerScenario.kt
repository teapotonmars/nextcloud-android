/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.nextcloud.client.account.User
import com.nextcloud.client.jobs.download.FileDownloadEventBroadcaster
import com.owncloud.android.datamodel.FileDataStorageManager
import com.owncloud.android.datamodel.FolderSyncSnapshot
import com.owncloud.android.lib.common.OwnCloudClient
import com.owncloud.android.lib.resources.files.CreateFolderRemoteOperation
import com.owncloud.android.lib.resources.files.ReadFileRemoteOperation
import com.owncloud.android.lib.resources.files.UploadFileRemoteOperation
import com.owncloud.android.lib.resources.files.model.RemoteFile
import com.owncloud.android.utils.FileStorageUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import java.io.File
import java.util.Collections

class FolderSyncServerScenario(
    private val context: Context,
    private val user: User,
    private val client: OwnCloudClient,
    private val storage: FileDataStorageManager
) {
    fun run(parent: String) {
        val prefix = parent + "inventory-${System.currentTimeMillis()}/"
        val folders = setOf(prefix, prefix + "a/", prefix + "a/deep/", prefix + "sibling/")
        val completed = Collections.synchronizedSet(mutableSetOf<String>())
        val failed = Collections.synchronizedSet(mutableSetOf<String>())
        val manager = LocalBroadcastManager.getInstance(context)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.getStringExtra(FileDownloadEventBroadcaster.EXTRA_ACCOUNT_NAME) != user.accountName) return
                val path = intent.getStringExtra(FileDownloadEventBroadcaster.EXTRA_REMOTE_PATH) ?: return
                if (path in folders) {
                    completed.add(path)
                    if (!intent.getBooleanExtra(FileDownloadEventBroadcaster.EXTRA_DOWNLOAD_RESULT, false)) {
                        failed.add(path)
                    }
                }
            }
        }
        manager.registerReceiver(receiver, IntentFilter(FileDownloadEventBroadcaster.ACTION_DOWNLOAD_COMPLETED))
        fun upload(path: String, text: String) {
            val source = File.createTempFile("inventory", ".txt", context.cacheDir)
            try {
                source.writeText(text)
                assertTrue(
                    UploadFileRemoteOperation(source.path, path, "text/plain", source.lastModified() / 1000)
                        .execute(client).isSuccess
                )
            } finally {
                source.delete()
            }
        }
        fun sync(expected: Map<String, String>) {
            completed.clear()
            failed.clear()
            val operation = SynchronizeFolderOperation(context, prefix, user, storage, false, true)
            assertTrue(operation.execute(context).isSuccess)
            completed.add(prefix)
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT
            while (SystemClock.elapsedRealtime() < deadline) {
                val filesReady = expected.all { (path, text) ->
                    val cached = storage.getFileByPath(path)
                    cached != null && cached.isDown && File(cached.storagePath).readText() == text
                }
                if (completed.containsAll(folders) && filesReady) {
                    assertTrue("Failed folders: $failed", failed.isEmpty())
                    return
                }
                SystemClock.sleep(POLL_INTERVAL)
            }
            fail("Incomplete discovery: ${folders - completed}")
        }
        fun certificates() = folders.associateWith {
            FolderSyncSnapshot.decode(storage.getFolderSyncSnapshot(it))!!.also { snapshot ->
                val row = storage.getFileByPath(it)
                val children = storage.getFolderContent(row, false)
                assertTrue(snapshot.matches(row, children, System.currentTimeMillis()))
            }
        }
        try {
            assertTrue(CreateFolderRemoteOperation(prefix + "a/deep/", true).execute(client).isSuccess)
            assertTrue(CreateFolderRemoteOperation(prefix + "sibling/", true).execute(client).isSuccess)
            val read = ReadFileRemoteOperation(prefix).execute(client)
            assertTrue(read.isSuccess)
            storage.saveFile(
                FileStorageUtils.fillOCFile(read.data.first() as RemoteFile).apply {
                    parentId = storage.getFileByPath(parent).fileId
                }
            )
            val first = prefix + "a/deep/first.txt"
            val sibling = prefix + "sibling/unchanged.txt"
            val expected = linkedMapOf(first to "first\n", sibling to "sibling\n")
            expected.forEach(::upload)
            sync(expected)
            val initial = certificates()
            val date = storage.getFileByPath(sibling).lastSyncDateForData
            sync(expected)
            assertEquals(initial, certificates())
            val added = prefix + "a/deep/addition.txt"
            upload(added, "deep addition\n")
            expected[added] = "deep addition\n"
            sync(expected)
            val changed = certificates()
            assertNotEquals(initial.getValue(prefix).etag, changed.getValue(prefix).etag)
            assertEquals(initial.getValue(prefix + "sibling/"), changed.getValue(prefix + "sibling/"))
            assertEquals(date, storage.getFileByPath(sibling).lastSyncDateForData)
            sync(expected)
            assertEquals(changed, certificates())
        } finally {
            manager.unregisterReceiver(receiver)
        }
    }

    companion object {
        private const val TIMEOUT = 90_000L
        private const val POLL_INTERVAL = 100L
    }
}
