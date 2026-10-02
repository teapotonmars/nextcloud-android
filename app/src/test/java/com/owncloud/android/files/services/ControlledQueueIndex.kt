/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.files.services

import java.util.concurrent.ConcurrentHashMap

internal class ControlledQueueIndex<K : Any, V : Any>(initial: Map<K, V>) : ConcurrentHashMap<K, V>(initial) {
    var beforePut: ((K) -> Unit)? = null
    var afterPut: ((K) -> Unit)? = null
    var beforeRemove: ((K) -> Unit)? = null

    override fun put(key: K, value: V): V? {
        beforePut?.invoke(key)
        return super.put(key, value).also { afterPut?.invoke(key) }
    }

    override fun remove(key: K): V? {
        beforeRemove?.invoke(key)
        return super.remove(key)
    }
}
