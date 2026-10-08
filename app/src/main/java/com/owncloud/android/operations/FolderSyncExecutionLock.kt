/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

import com.owncloud.android.lib.common.operations.RemoteOperationResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.function.BooleanSupplier
import java.util.function.Supplier

object FolderSyncExecutionLock {
    private const val CANCELLATION_CHECK_INTERVAL_MS = 100L
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    @JvmStatic
    fun execute(
        accountName: String,
        cancelled: BooleanSupplier,
        operation: Supplier<RemoteOperationResult<*>>
    ): RemoteOperationResult<*> {
        val lock = locks.computeIfAbsent(accountName) { ReentrantLock(true) }
        val acquired = try {
            acquire(lock, cancelled)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return cancelledResult()
        return try {
            if (cancelled.asBoolean) cancelledResult() else operation.get()
        } finally {
            lock.unlock()
        }
    }

    private fun acquire(lock: ReentrantLock, cancelled: BooleanSupplier): Boolean {
        while (!cancelled.asBoolean) {
            if (lock.tryLock(CANCELLATION_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    private fun cancelledResult() = RemoteOperationResult<Any>(RemoteOperationResult.ResultCode.CANCELLED)
}
