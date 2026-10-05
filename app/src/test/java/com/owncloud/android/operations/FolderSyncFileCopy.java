/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations;

import com.owncloud.android.datamodel.OCFile;

final class FolderSyncFileCopy {
    static OCFile copy(OCFile file) {
        OCFile copy = new OCFile(file.getRemotePath());
        copy.setMimeType(file.getMimeType());
        copy.setDecryptedRemotePath(file.getDecryptedRemotePath());
        copy.setReadOnly(file.isReadOnly());
        copy.setLastSyncDateForProperties(file.getLastSyncDateForProperties());
        copy.setSharees(file.getSharees());
        copy.setEtag(file.getEtag());
        copy.setModificationTimestamp(file.getModificationTimestamp());
        copy.setEtagOnServer(file.getEtagOnServer());
        copy.setRemoteId(file.getRemoteId());
        copy.setMountType(file.getMountType());
        copy.setEncrypted(file.isEncrypted());
        copy.setPermissions(file.getPermissions());
        copy.setFileLength(file.getFileLength());
        copy.setInternalFolderSyncTimestamp(file.getInternalFolderSyncTimestamp());
        copy.setInternalFolderSyncResult(file.getInternalFolderSyncResult());
        return copy;
    }
}
