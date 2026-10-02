/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.files.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class IndexedForestTest {
    @Test
    fun statusViewIsReadOnlyAndReflectsNewOperations() {
        val forest = IndexedForest<String>()
        forest.putIfAbsent(ACCOUNT, PARENT, "parent")
        val status = forest.all
        org.junit.Assert.assertThrows(UnsupportedOperationException::class.java) { status.clear() }
        assertEquals("parent", forest.get(ACCOUNT, PARENT))
        forest.putIfAbsent(ACCOUNT, PARENT + "child/", "child")
        assertTrue(status.values.any { it.payload == "child" })
    }

    @Test
    fun simultaneousChildrenKeepTheirIndexedParentUntilBothFinish() {
        val forest = IndexedForest<String>()
        val index = ControlledQueueIndex(forest.all)
        IndexedForest::class.java.getDeclaredField("mMap").apply { isAccessible = true }.set(forest, index)
        val firstParentWrite = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondParentWritten = CountDownLatch(1)
        val first = AtomicBoolean(true)
        index.beforePut = { key ->
            if (key == ACCOUNT_KEY_PREFIX + PARENT && first.compareAndSet(true, false)) {
                firstParentWrite.countDown()
                assertTrue(releaseFirst.await(DEADLINE_SECONDS, TimeUnit.SECONDS))
            }
        }
        index.afterPut = { key ->
            if (key == ACCOUNT_KEY_PREFIX + PARENT && releaseFirst.count > 0) secondParentWritten.countDown()
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val left = executor.submit { forest.putIfAbsent(ACCOUNT, PARENT + "left/", "left") }
            if (!firstParentWrite.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
                left.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
                throw AssertionError("Parent insertion did not reach the barrier")
            }
            val right = executor.submit { forest.putIfAbsent(ACCOUNT, PARENT + "right/", "right") }
            secondParentWritten.await(INTERLEAVING_MILLIS, TimeUnit.MILLISECONDS)
            releaseFirst.countDown()
            left.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
            right.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
            assertEquals("left", forest.get(ACCOUNT, PARENT + "left/"))
            assertEquals("right", forest.get(ACCOUNT, PARENT + "right/"))
            forest.removePayload(ACCOUNT, PARENT + "left/")
            forest.removePayload(ACCOUNT, PARENT + "right/")
            assertTrue(forest.all.isEmpty())
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun childAcceptedDuringParentCompletionRemainsQueued() {
        val forest = IndexedForest<String>()
        forest.putIfAbsent(ACCOUNT, PARENT, "parent")
        val index = ControlledQueueIndex(forest.all)
        IndexedForest::class.java.getDeclaredField("mMap").apply { isAccessible = true }.set(forest, index)
        val beforeRemoval = CountDownLatch(1)
        val releaseRemoval = CountDownLatch(1)
        val childAccepted = CountDownLatch(1)
        index.beforeRemove = { key ->
            if (key == ACCOUNT_KEY_PREFIX + PARENT) {
                beforeRemoval.countDown()
                assertTrue(releaseRemoval.await(DEADLINE_SECONDS, TimeUnit.SECONDS))
            }
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val completion = executor.submit { forest.removePayload(ACCOUNT, PARENT) }
            if (!beforeRemoval.await(DEADLINE_SECONDS, TimeUnit.SECONDS)) {
                completion.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
                throw AssertionError("Parent completion did not reach the barrier")
            }
            val addition = executor.submit {
                forest.putIfAbsent(ACCOUNT, PARENT + "child/", "child")
                childAccepted.countDown()
            }
            childAccepted.await(INTERLEAVING_MILLIS, TimeUnit.MILLISECONDS)
            releaseRemoval.countDown()
            completion.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
            addition.get(DEADLINE_SECONDS, TimeUnit.SECONDS)
            assertEquals("child", forest.get(ACCOUNT, PARENT + "child/"))
            assertNull(forest.get(ACCOUNT, PARENT))
            forest.removePayload(ACCOUNT, PARENT + "child/")
            assertTrue(forest.all.isEmpty())
        } finally {
            releaseRemoval.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun lastChildCompletionRetainsPendingParentOperation() {
        val forest = IndexedForest<String>()
        forest.putIfAbsent(ACCOUNT, PARENT, "parent")
        forest.putIfAbsent(ACCOUNT, PARENT + "child/", "child")
        forest.removePayload(ACCOUNT, PARENT + "child/")
        assertEquals("parent", forest.get(ACCOUNT, PARENT))
        forest.removePayload(ACCOUNT, PARENT)
        assertTrue(forest.all.isEmpty())
    }

    @Test
    fun parentCompletionKeepsChildrenAndPrunesAfterTheirCompletion() {
        val forest = IndexedForest<String>()
        forest.putIfAbsent(ACCOUNT, PARENT, "parent")
        forest.putIfAbsent(ACCOUNT, PARENT + "child/", "child")
        forest.removePayload(ACCOUNT, PARENT)
        assertTrue(forest.contains(ACCOUNT, PARENT))
        assertNull(forest.get(ACCOUNT, PARENT))
        assertEquals("child", forest.get(ACCOUNT, PARENT + "child/"))
        forest.removePayload(ACCOUNT, PARENT + "child/")
        assertFalse(forest.contains(ACCOUNT, PARENT))
    }

    @Test
    fun subtreeCancellationKeepsSiblingAndOtherAccount() {
        val forest = IndexedForest<String>()
        forest.putIfAbsent(ACCOUNT, PARENT + "child/", "child")
        forest.putIfAbsent(ACCOUNT, "/sibling/", "sibling")
        forest.putIfAbsent(OTHER_ACCOUNT, PARENT + "child/", "other")
        forest.remove(ACCOUNT, PARENT)
        assertNull(forest.get(ACCOUNT, PARENT + "child/"))
        assertEquals("sibling", forest.get(ACCOUNT, "/sibling/"))
        assertEquals("other", forest.get(OTHER_ACCOUNT, PARENT + "child/"))
        forest.remove(ACCOUNT)
        assertEquals("other", forest.get(OTHER_ACCOUNT, PARENT + "child/"))
    }

    @Test
    fun duplicatePendingOperationKeepsOriginalPayload() {
        val forest = IndexedForest<String>()
        forest.putIfAbsent(ACCOUNT, PARENT, "first")
        assertNull(forest.putIfAbsent(ACCOUNT, PARENT, "second"))
        assertEquals("first", forest.get(ACCOUNT, PARENT))
    }

    @Test
    fun cancelledOperationCannotCompleteItsReplacement() {
        val forest = IndexedForest<Any>()
        val original = Any()
        val replacement = Any()
        forest.putIfAbsent(ACCOUNT, PARENT, original)
        forest.remove(ACCOUNT, PARENT)
        forest.putIfAbsent(ACCOUNT, PARENT, replacement)
        forest.removePayload(ACCOUNT, PARENT, original)
        assertEquals(replacement, forest.get(ACCOUNT, PARENT))
        forest.removePayload(ACCOUNT, PARENT, replacement)
        assertTrue(forest.all.isEmpty())
    }

    @Test
    fun removingAccountDoesNotCancelAnAccountWithTheSamePrefix() {
        val forest = IndexedForest<String>()
        forest.putIfAbsent(ACCOUNT, PARENT, "first")
        forest.putIfAbsent(ACCOUNT + "/other", PARENT, "second")
        forest.remove(ACCOUNT)
        assertNull(forest.get(ACCOUNT, PARENT))
        assertEquals("second", forest.get(ACCOUNT + "/other", PARENT))
    }

    @Test
    fun accountAndRemotePathBoundariesCannotCollide() {
        val forest = IndexedForest<String>()
        forest.putIfAbsent(ACCOUNT, "/other/", "first")
        forest.putIfAbsent(ACCOUNT + "/other", "/", "second")
        assertEquals("first", forest.get(ACCOUNT, "/other/"))
        assertEquals("second", forest.get(ACCOUNT + "/other", "/"))
        forest.remove(ACCOUNT)
        assertEquals("second", forest.get(ACCOUNT + "/other", "/"))
    }

    private companion object {
        const val ACCOUNT = "account"
        const val OTHER_ACCOUNT = "other"
        val ACCOUNT_KEY_PREFIX = "${ACCOUNT.length}:$ACCOUNT"
        const val PARENT = "/parent/"
        const val DEADLINE_SECONDS = 5L
        const val INTERLEAVING_MILLIS = 300L
    }
}
