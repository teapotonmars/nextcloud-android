/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import com.owncloud.android.lib.common.operations.RemoteOperationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class FolderSyncExecutionLockTest {
    @Test
    fun manualAndWorkerOperationsForOneAccountCannotOverlap() = withActiveOperation { account, release, executor ->
        val waiting = CountDownLatch(1)
        val entered = AtomicBoolean(false)
        val next = executor.submit<RemoteOperationResult<*>> {
            FolderSyncExecutionLock.execute(account, {
                waiting.countDown()
                false
            }) {
                entered.set(true)
                success()
            }
        }
        assertTrue(waiting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertFalse(entered.get())
        release.countDown()
        assertTrue(next.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isSuccess)
        assertTrue(entered.get())
    }

    @Test
    fun anotherAccountCanRunWhileTheFirstAccountIsBusy() = withActiveOperation { _, _, executor ->
        val next = executor.submit<RemoteOperationResult<*>> {
            FolderSyncExecutionLock.execute("other-account", { false }) { success() }
        }
        assertTrue(next.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isSuccess)
    }

    @Test
    fun stoppedWorkerLeavesTheWaitWithoutExecutingOrUnlockingTheActiveOperation() =
        withActiveOperation { account, _, executor ->
            val waiting = CountDownLatch(1)
            val cancelled = AtomicBoolean(false)
            val entered = AtomicBoolean(false)
            val next = executor.submit<RemoteOperationResult<*>> {
                FolderSyncExecutionLock.execute(account, {
                    waiting.countDown()
                    cancelled.get()
                }) {
                    entered.set(true)
                    success()
                }
            }
            assertTrue(waiting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            cancelled.set(true)
            assertEquals(RemoteOperationResult.ResultCode.CANCELLED, next.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).code)
            assertFalse(entered.get())
        }

    @Test
    fun failureReleasesTheAccountLock() {
        val failure = IllegalStateException("folder failed")
        try {
            FolderSyncExecutionLock.execute("failed-account", { false }) { throw failure }
            org.junit.Assert.fail("Expected failure")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual)
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val next = executor.submit<RemoteOperationResult<*>> {
                FolderSyncExecutionLock.execute("failed-account", { false }) { success() }
            }
            assertTrue(next.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isSuccess)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun withActiveOperation(test: (String, CountDownLatch, java.util.concurrent.ExecutorService) -> Unit) {
        val executor = Executors.newFixedThreadPool(2)
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val account = "shared-account"
        val active = executor.submit<RemoteOperationResult<*>> {
            FolderSyncExecutionLock.execute(account, { false }) {
                acquired.countDown()
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                success()
            }
        }
        try {
            assertTrue(acquired.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            test(account, release, executor)
        } finally {
            release.countDown()
            assertTrue(active.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).isSuccess)
            executor.shutdownNow()
        }
    }

    private fun success() = RemoteOperationResult<Any>(RemoteOperationResult.ResultCode.OK)

    companion object {
        private const val TIMEOUT_SECONDS = 5L
    }
}
