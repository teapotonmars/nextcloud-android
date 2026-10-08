/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.datamodel

import com.google.gson.Gson
import com.owncloud.android.lib.common.network.WebdavEntry
import org.apache.commons.codec.binary.Hex
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class FolderSyncSnapshot(val etag: String, val inventory: String, val createdAt: Long) {
    fun matches(folder: OCFile, children: List<OCFile>, now: Long): Boolean =
        createdAt > 0 && now >= createdAt && now - createdAt < MAX_AGE && eligible(folder, children) &&
            inventory == fingerprint(folder, children)

    fun encode(): String = gson.toJson(this)

    companion object {
        private val MAX_AGE = TimeUnit.HOURS.toMillis(24)
        private val gson = Gson()

        @JvmStatic
        fun decode(value: String?): FolderSyncSnapshot? = runCatching {
            gson.fromJson(value, FolderSyncSnapshot::class.java)?.takeIf {
                !it.etag.isNullOrEmpty() && !it.inventory.isNullOrEmpty() && it.createdAt > 0
            }
        }.getOrNull()

        @JvmStatic
        fun eligible(folder: OCFile, children: List<OCFile>): Boolean =
            folder.isFolder && !folder.isEncrypted && !folder.mounted() &&
                folder.mountType == WebdavEntry.MountType.INTERNAL &&
                children.all { !it.etagOnServer.isNullOrEmpty() }

        @JvmStatic
        fun supportsSkipping(folder: OCFile, storage: FileDataStorageManager): Boolean {
            var current: OCFile? = folder
            while (current != null && current.remotePath != OCFile.ROOT_PATH && eligible(current, emptyList())) {
                val parentPath = current.remotePath.trimEnd('/').substringBeforeLast('/', "") + "/"
                current = storage.getFileByPath(parentPath)
            }
            return current != null && eligible(current, emptyList())
        }

        @JvmStatic
        fun fingerprint(folder: OCFile, children: List<OCFile>): String {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeLong(folder.fileId)
                output.writeUTF(folder.remotePath)
                output.writeUTF(folder.remoteId.orEmpty())
                output.writeUTF(folder.permissions.orEmpty())
                for (child in children.sortedBy { it.remotePath }) {
                    output.writeUTF(child.remotePath)
                    output.writeUTF(child.remoteId.orEmpty())
                    output.writeUTF(child.etagOnServer.orEmpty())
                    output.writeUTF(child.mimeType.orEmpty())
                    output.writeUTF(child.permissions.orEmpty())
                    output.writeLong(child.modificationTimestamp)
                    output.writeLong(if (child.isFolder) 0 else child.fileLength)
                    output.writeBoolean(child.isEncrypted)
                    output.writeInt(child.mountType?.ordinal ?: -1)
                }
            }
            return String(Hex.encodeHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())))
        }
    }
}
