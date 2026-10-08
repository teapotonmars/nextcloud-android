/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations

enum class FolderSyncMode(val isRecursive: Boolean, val isForced: Boolean) {
    SINGLE_FOLDER(false, false),
    RECURSIVE_CACHED(true, false),
    RECURSIVE_FORCED(true, true)
}
