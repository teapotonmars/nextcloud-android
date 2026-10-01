/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations;

import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

final class FolderSyncRowMock implements AutoCloseable {
    FolderSyncRowMock(FileDataStorageManager storage, Map<String, OCFile> local) {
        when(storage.saveFile(any())).thenAnswer(call -> {
            OCFile file = call.getArgument(0);
            OCFile stored = local.get(file.getRemotePath());
            OCFile persisted = file;
            if (file.isFolder()) {
                persisted = new OCFile(file.getRemotePath());
                persisted.setMimeType(file.getMimeType());
                persisted.setFileId(file.getFileId());
                persisted.setParentId(file.getParentId());
                persisted.setRemoteId(file.getRemoteId());
                persisted.setPermissions(file.getPermissions());
                persisted.setFileLength(file.getFileLength());
                persisted.setModificationTimestamp(file.getModificationTimestamp());
                persisted.setLastSyncDateForData(file.getLastSyncDateForData());
                persisted.setModificationTimestampAtLastSyncForData(file.getModificationTimestampAtLastSyncForData());
                persisted.setInternalFolderSyncTimestamp(file.getInternalFolderSyncTimestamp());
                persisted.setInternalFolderSyncResult(file.getInternalFolderSyncResult());
            }
            if (file.isFolder() && stored != null) {
                persisted.setEtag(stored.getEtag());
                persisted.setStoragePath(stored.getStoragePath());
            }
            local.put(file.getRemotePath(), persisted);
            return true;
        });

    }

    @Override
    public void close() { }
}
