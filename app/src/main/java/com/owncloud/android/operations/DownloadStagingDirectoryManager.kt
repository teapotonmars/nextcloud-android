/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import com.owncloud.android.lib.common.utils.Log_OC
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal class DownloadStagingDirectoryManager {
    private val initializedRoots = mutableSetOf<Path>()

    @Synchronized
    fun create(root: File): File {
        prepare(root)
        return File(root, "download-${UUID.randomUUID()}")
    }

    @Synchronized
    fun prepare(root: File) {
        val rootPath = root.toPath().toAbsolutePath().normalize()
        // All download entry points run in the main process. Sweep before handing out its first staging path,
        // so concurrent downloads cannot have their staging files mistaken for leftovers from a dead process.
        if (initializedRoots.add(rootPath)) cleanAbandonedDirectories(rootPath.toFile())
    }

    private fun cleanAbandonedDirectories(root: File) {
        root.listFiles()?.filter {
            stagingName.matches(it.name) && Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS)
        }?.forEach { directory ->
            try {
                Files.walkFileTree(
                    directory.toPath(),
                    object : SimpleFileVisitor<Path>() {
                        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                            Files.delete(file)
                            return FileVisitResult.CONTINUE
                        }

                        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                            if (exc != null) throw exc
                            Files.delete(dir)
                            return FileVisitResult.CONTINUE
                        }
                    }
                )
            } catch (exception: IOException) {
                Log_OC.e(TAG, "Unable to remove abandoned download staging directory", exception)
            }
        }
    }

    companion object {
        private const val TAG = "DownloadStagingDirectoryManager"
        private val stagingName = Regex("download-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val instance = DownloadStagingDirectoryManager()
    }
}
