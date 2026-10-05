/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations;

import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.datamodel.FolderSyncLocalState;
import java.util.Map;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;

final class FolderSyncRowMock implements AutoCloseable {
    static OCFile listingRow(OCFile listed, OCFile current) {
        OCFile saved = listed;
        if (current != null) {
            saved.setInternalFolderSyncTimestamp(current.getInternalFolderSyncTimestamp());
            saved.setInternalFolderSyncResult(current.getInternalFolderSyncResult());
        }
        return saved;
    }

    FolderSyncRowMock(FileDataStorageManager storage, Map<String, OCFile> local) {
        doAnswer(call -> {
            OCFile remote = call.getArgument(0);
            FolderSyncLocalState.preserve(remote, call.getArgument(1));
            remote.setEtagOnServer(remote.getEtag());
            storage.saveFolder(remote, call.getArgument(2), call.getArgument(3));
            storage.updateFolderSize(remote);
            return null;
        }).when(storage).saveSynchronizedFolder(any(), any(), any(), any());
        when(storage.getFileById(anyLong())).thenAnswer(call -> {
            long id = call.getArgument(0);
            OCFile row = local.values().stream().filter(file -> file.getFileId() == id).findFirst().orElse(null);
            return row == null ? null : spy(row);
        });
        when(storage.saveFile(any())).thenAnswer(call -> {
            OCFile file = call.getArgument(0);
            OCFile stored = local.get(file.getRemotePath());
            OCFile persisted = spy(file);
            if (file.isFolder() && stored != null) {
                persisted.setEtag(stored.getEtag());
                persisted.setStoragePath(stored.getStoragePath());
            }
            local.put(file.getRemotePath(), persisted);
            return true;
        });
        doAnswer(call -> {
            OCFile folder = call.getArgument(0);
            local.get(folder.getRemotePath()).setFileLength(folder.getFileLength());
            return null;
        }).when(storage).updateFolderSize(any());
        doAnswer(call -> {
            OCFile file = call.getArgument(0);
            file.setLastSyncDateForData(call.getArgument(1));
            return null;
        }).when(storage).updateFolderSyncTime(any(), anyLong());
    }

    @Override
    public void close() { }
}
