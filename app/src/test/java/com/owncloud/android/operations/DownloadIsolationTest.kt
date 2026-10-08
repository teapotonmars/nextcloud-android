/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import android.content.Context
import android.os.Handler
import com.nextcloud.client.account.User
import com.nextcloud.common.NextcloudClient
import com.nextcloud.utils.extensions.toNextcloudClient
import com.owncloud.android.datamodel.OCFile
import com.owncloud.android.lib.common.OwnCloudClient
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.lib.resources.files.DownloadFileRemoteOperation
import com.owncloud.android.utils.FileExportUtils
import com.owncloud.android.utils.FileStorageUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

@Suppress("DEPRECATION")
class DownloadIsolationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val user = mock<User> { on { accountName }.thenReturn("download-account") }
    private val context = mock<Context>()
    private val client = mock<OwnCloudClient>()

    @Test
    fun interleavedDownloadsCannotPublishAFileThatAnotherTransferIsStillWriting() = withDownloadEnvironment {
        val first = download()
        val second = download()
        assertNotEquals(first.tmpFolder, second.tmpFolder)
        mockConstruction(DownloadFileRemoteOperation::class.java) { remote, construction ->
            val staging = File(construction.arguments()[1] as String, "Books/file.txt")
            whenever(remote.execute(any<NextcloudClient>())).thenAnswer {
                staging.parentFile!!.mkdirs()
                if (construction.count == 1) {
                    staging.outputStream().use { stream ->
                        stream.write("first-".toByteArray())
                        assertTrue(second.execute(client).isSuccess)
                        assertEquals("second-version", File(second.savePath).readText())
                        stream.write("version".toByteArray())
                        stream.flush()
                        assertEquals("second-version", File(second.savePath).readText())
                    }
                } else {
                    staging.writeText("second-version")
                }
                result(RemoteOperationResult.ResultCode.OK)
            }
        }.use {
            assertTrue(first.execute(client).isSuccess)
        }
        assertEquals("first-version", File(first.savePath).readText())
        assertFalse(File(first.tmpFolder).exists())
        assertFalse(File(second.tmpFolder).exists())
    }

    @Test
    fun failedTransferRemovesOnlyItsOwnPartialDownload() = withDownloadEnvironment {
        val failed = download()
        val other = download()
        val otherPartial = File(other.tmpPath).apply {
            parentFile!!.mkdirs()
            writeText("other-active-transfer")
        }
        mockConstruction(DownloadFileRemoteOperation::class.java) { remote, _ ->
            whenever(remote.execute(any<NextcloudClient>())).thenAnswer {
                File(failed.tmpPath).apply {
                    parentFile!!.mkdirs()
                    writeText("partial")
                }
                result(RemoteOperationResult.ResultCode.CANCELLED)
            }
        }.use {
            assertEquals(RemoteOperationResult.ResultCode.CANCELLED, failed.execute(client).code)
        }
        assertFalse(File(failed.tmpFolder).exists())
        assertEquals("other-active-transfer", otherPartial.readText())
    }

    @Test
    fun exportReadsThePrivateDownloadBeforeStagingIsRemoved() = withDownloadEnvironment {
        val export = download().apply { downloadType = DownloadType.EXPORT }
        mockConstruction(DownloadFileRemoteOperation::class.java) { remote, _ ->
            whenever(remote.execute(any<NextcloudClient>())).thenAnswer {
                File(export.tmpPath).apply {
                    parentFile!!.mkdirs()
                    writeText("export-content")
                }
                result(RemoteOperationResult.ResultCode.OK)
            }
        }.use {
            mockConstruction(FileExportUtils::class.java) { exporter, _ ->
                doAnswer { call ->
                    assertEquals("export-content", call.getArgument<File>(4).readText())
                    null
                }.whenever(exporter).exportFile(any(), any(), any(), isNull(), any())
            }.use { exporters ->
                assertTrue(export.execute(client).isSuccess)
                assertEquals(1, exporters.constructed().size)
                val resolver = context.contentResolver
                verify(exporters.constructed().single()).exportFile(
                    eq("file.txt"),
                    eq("text/plain"),
                    eq(resolver),
                    isNull(),
                    eq(File(export.tmpPath))
                )
            }
        }
        assertFalse(File(export.tmpFolder).exists())
    }

    private fun download() = DownloadFileOperation(
        user,
        OCFile("/Books/file.txt").apply {
            storagePath = temporaryFolder.root.resolve("file.txt").path
            mimeType = "text/plain"
        },
        context
    )

    private fun withDownloadEnvironment(test: () -> Unit) {
        whenever(context.contentResolver).thenReturn(mock())
        mockStatic(Log_OC::class.java).use {
            mockConstruction(Handler::class.java).use { withDownloadClients(test) }
        }
    }

    private fun withDownloadClients(test: () -> Unit) {
        mockStatic(FileStorageUtils::class.java).use { storage ->
            storage.`when`<String> { FileStorageUtils.getTemporalPath(user.accountName) }
                .thenReturn(temporaryFolder.root.path)
            storage.`when`<Boolean> { FileStorageUtils.isValidExtFilename("file.txt") }.thenReturn(true)
            mockStatic(Class.forName("com.nextcloud.utils.extensions.OwnCloudClientExtensionsKt")).use { clients ->
                clients.`when`<NextcloudClient> { client.toNextcloudClient(context) }
                    .thenReturn(mock<NextcloudClient>())
                test()
            }
        }
    }

    private fun result(code: RemoteOperationResult.ResultCode) = RemoteOperationResult<Any>(code)
}
