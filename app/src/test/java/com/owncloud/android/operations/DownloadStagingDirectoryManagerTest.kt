/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DownloadStagingDirectoryManagerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun firstDownloadAfterRestartRemovesAbandonedStagingButPreservesOtherTemporaryFiles() {
        val root = temporaryFolder.newFolder("account")
        val abandoned = partial(root)
        val upload = File(root, "Books/upload.txt").apply {
            parentFile!!.mkdirs()
            writeText("upload")
        }
        val unrelated = File(root, "download-incomplete-name").apply { mkdirs() }

        val next = DownloadStagingDirectoryManager().create(root)

        assertFalse(abandoned.exists())
        assertFalse(abandoned.parentFile!!.exists())
        assertEquals("upload", upload.readText())
        assertTrue(unrelated.exists())
        assertEquals(root, next.parentFile)
    }

    @Test
    fun workerPreparationCanReclaimSpaceBeforeDownloadingAndPreservesActiveTransfers() {
        val root = temporaryFolder.newFolder("account")
        val abandoned = partial(root)
        val manager = DownloadStagingDirectoryManager()

        manager.prepare(root)

        assertFalse(abandoned.exists())
        val active = manager.create(root).resolve("partial.txt").apply {
            parentFile!!.mkdirs()
            writeText("active")
        }
        manager.prepare(root)
        assertEquals("active", active.readText())
    }

    @Test
    fun laterDownloadsInTheSameProcessPreserveActiveStaging() {
        val root = temporaryFolder.newFolder("account")
        val manager = DownloadStagingDirectoryManager()
        val active = manager.create(root).resolve("partial.txt").apply {
            parentFile!!.mkdirs()
            writeText("active")
        }

        manager.create(root)

        assertEquals("active", active.readText())
        DownloadStagingDirectoryManager().create(root)
        assertFalse(active.exists())
    }

    @Test
    fun eachAccountPathIsCleanedBeforeItsFirstDownload() {
        val firstRoot = temporaryFolder.newFolder("first")
        val secondRoot = temporaryFolder.newFolder("second")
        val manager = DownloadStagingDirectoryManager()
        manager.create(firstRoot)
        val abandoned = partial(secondRoot)

        manager.create(secondRoot)

        assertFalse(abandoned.exists())
    }

    @Test
    fun cleanupDoesNotFollowLinksOutsideAnAbandonedDirectory() {
        val root = temporaryFolder.newFolder("account")
        val outside = temporaryFolder.newFolder("outside")
        val valuable = outside.resolve("valuable.txt").apply { writeText("keep") }
        val abandoned = partial(root).parentFile!!
        Files.createSymbolicLink(abandoned.resolve("link").toPath(), outside.toPath())
        val rootLink = root.resolve("download-${UUID.randomUUID()}")
        Files.createSymbolicLink(rootLink.toPath(), outside.toPath())

        DownloadStagingDirectoryManager().create(root)

        assertFalse(abandoned.exists())
        assertTrue(Files.isSymbolicLink(rootLink.toPath()))
        assertEquals("keep", valuable.readText())
    }

    @Test
    fun concurrentAllocationsCannotCleanOneAnothersActiveFiles() {
        val root = temporaryFolder.newFolder("account")
        val abandoned = partial(root)
        val manager = DownloadStagingDirectoryManager()
        val executor = Executors.newFixedThreadPool(4)
        try {
            val downloads = executor.invokeAll(
                List(20) {
                    Callable {
                        manager.create(root).resolve("partial.txt").apply {
                            parentFile!!.mkdirs()
                            writeText("active")
                        }
                    }
                },
                10,
                TimeUnit.SECONDS
            ).map { it.get() }

            assertFalse(abandoned.exists())
            assertEquals(downloads.size, downloads.toSet().size)
            downloads.forEach { assertEquals("active", it.readText()) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun partial(root: File) = root.resolve("download-${UUID.randomUUID()}/partial.txt").apply {
        parentFile!!.mkdirs()
        writeText("abandoned")
    }
}
