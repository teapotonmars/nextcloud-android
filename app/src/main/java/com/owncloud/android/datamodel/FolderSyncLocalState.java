/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.datamodel;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public final class FolderSyncLocalState {
    private FolderSyncLocalState() { }

    @SuppressFBWarnings(value = "CE",
        justification = "This mapper copies synchronization-owned fields between OCFile rows.")
    public static void preserve(OCFile remoteFolder, OCFile localFolder) {
        remoteFolder.setDecryptedRemotePath(localFolder.getDecryptedRemotePath());
        remoteFolder.setReadOnly(localFolder.isReadOnly());
        remoteFolder.setLastSyncDateForProperties(localFolder.getLastSyncDateForProperties());
        // A parent listing can enrich these through the unified API when PROPFIND omits them.
        remoteFolder.setSharees(localFolder.getSharees());
        remoteFolder.setInternalFolderSyncTimestamp(localFolder.getInternalFolderSyncTimestamp());
        remoteFolder.setInternalFolderSyncResult(localFolder.getInternalFolderSyncResult());
        remoteFolder.setLastSyncDateForData(localFolder.getLastSyncDateForData());
        remoteFolder.setModificationTimestampAtLastSyncForData(localFolder.getModificationTimestampAtLastSyncForData());
        remoteFolder.setEncrypted(localFolder.isEncrypted());
    }
}
