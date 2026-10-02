/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.services

import com.owncloud.android.lib.common.OwnCloudClient
import com.owncloud.android.lib.common.operations.RemoteOperationResult
import com.owncloud.android.lib.common.utils.Log_OC
import com.owncloud.android.operations.SynchronizeFolderOperation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SyncFolderHandlerTest {
    @Test
    fun runtimeFailureBecomesAnOperationFailureAndNextOperationCanRun() {
        mockStatic(Log_OC::class.java).use {
            val client = mock<OwnCloudClient>()
            val failed = mock<SynchronizeFolderOperation>()
            whenever(failed.execute(client)).thenThrow(IllegalStateException("invalid metadata"))
            assertFalse(SyncFolderHandler.executeQueuedOperation(failed, client).isSuccess)
            val next = mock<SynchronizeFolderOperation>()
            val success = RemoteOperationResult<Any>(RemoteOperationResult.ResultCode.OK)
            whenever(next.execute(client)).thenReturn(success)
            assertSame(success, SyncFolderHandler.executeQueuedOperation(next, client))
        }
    }
}
