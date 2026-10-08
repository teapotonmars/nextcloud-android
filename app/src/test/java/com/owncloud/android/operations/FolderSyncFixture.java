/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations;
import android.content.Context;
import android.text.TextUtils;
import com.nextcloud.client.account.User;
import com.nextcloud.client.jobs.download.FileDownloadHelper;
import com.nextcloud.client.jobs.folderDownload.FolderDownloadWorkerNotificationManager;
import com.nextcloud.utils.extensions.ExtensionsKt;
import com.nextcloud.utils.share.UnifiedShareSharees;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.files.ReadFileRemoteOperation;
import com.owncloud.android.lib.resources.files.ReadFolderRemoteOperation;
import com.owncloud.android.lib.resources.files.model.RemoteFile;
import com.owncloud.android.utils.FileStorageUtils;
import com.owncloud.android.utils.MimeTypeUtil;
import org.junit.rules.TemporaryFolder;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
final class FolderSyncFixture implements AutoCloseable {
    static final String ROOT = "/root/";
    final Map<String, Object> encryptedMetadata = new HashMap<>();
    final Map<String, OCFile> local = new LinkedHashMap<>();
    final Map<String, OCFile> remote = new LinkedHashMap<>();
    final Map<String, FolderSyncMode> queuedModes = new HashMap<>();
    final Set<String> failedListings = new HashSet<>();
    final Set<String> failedDownloads = new HashSet<>();
    final List<String> downloads = new ArrayList<>();
    final List<String> uploads = new ArrayList<>();
    final List<String> listings = new ArrayList<>();
    final List<String> checks = new ArrayList<>();
    final List<Boolean> recursiveModes = new ArrayList<>();
    final List<String> fileChecks = new ArrayList<>();
    final List<RemoteOperationResult> results = new ArrayList<>();
    final Context context = mock(Context.class);
    final User user = mock(User.class);
    final FileDataStorageManager storage = mock(FileDataStorageManager.class);
    final OwnCloudClient client = mock(OwnCloudClient.class);
    Runnable afterListing = () -> { };
    java.util.function.Consumer<String> beforeFolder = path -> { };
    int notificationUpdates;
    int folderRowsWritten;
    private final TemporaryFolder files;
    private final List<AutoCloseable> mocks = new ArrayList<>();
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final Map<RemoteFile, OCFile> snapshots = new IdentityHashMap<>();
    private long nextId = 1;
    FolderSyncFixture(TemporaryFolder files) throws Exception {
        this.files = files;
        MockedStatic<TextUtils> text = keep(mockStatic(TextUtils.class));
        text.when(() -> TextUtils.isEmpty(nullable(CharSequence.class)))
            .thenAnswer(call -> call.getArgument(0) == null || call.getArgument(0).toString().isEmpty());
        keep(mockStatic(Log_OC.class));
        keep(mockStatic(MimeTypeUtil.class));
        keep(mockStatic(ExtensionsKt.class));
        keep(mockStatic(UnifiedShareSharees.class));
        keep(mockConstruction(FolderDownloadWorkerNotificationManager.class, (manager, ignored) ->
            doAnswer(call -> {
                notificationUpdates++;
                return null;
            }).when(manager).showProgressNotification(anyString(), anyString(), anyInt(), anyInt())));
        resetDownloadHelper();
        keep(new SyncUploadMock(uploads));
        keep(mockConstruction(FileDownloadHelper.class, (helper, ignored) ->
            doAnswer(call -> {
                OCFile file = call.getArgument(0);
                OCFile server = remote.get(file.getRemotePath());
                file.setEtag(server.getEtag());
                file.setEtagOnServer(server.getEtag());
                file.setModificationTimestamp(server.getModificationTimestamp());
                file.setStoragePath(files.newFile().getAbsolutePath());
                file.setLastSyncDateForData(System.currentTimeMillis());
                file.setModificationTimestampAtLastSyncForData(server.getModificationTimestamp());
                local.put(file.getRemotePath(), file);
                return null;
            }).when(helper).saveFile(any(), any(), any())));
        keep(mockConstruction(DownloadFileOperation.class, (download, construction) -> {
            OCFile file = (OCFile) construction.arguments().get(1);
            when(download.execute(client)).thenAnswer(call -> {
                downloads.add(file.getRemotePath());
                return result(!failedDownloads.contains(file.getRemotePath()), List.of());
            });
        }));
        MockedStatic<FileStorageUtils> fileUtils = keep(mockStatic(FileStorageUtils.class));
        fileUtils.when(() -> FileStorageUtils.fillOCFile(any(RemoteFile.class)))
            .thenAnswer(call -> copy(snapshots.get(call.getArgument(0))));
        fileUtils.when(() -> FileStorageUtils.checkEncryptionStatus(any(OCFile.class), any()))
            .thenAnswer(call -> encryptedMetadata.containsKey(((OCFile) call.getArgument(0)).getRemotePath()));
        MockedStatic<RefreshFolderOperation> refresh = keep(mockStatic(RefreshFolderOperation.class));
        refresh.when(() -> RefreshFolderOperation.getDecryptedFolderMetadata(anyBoolean(), any(), any(), any(), any()))
            .thenAnswer(call -> encryptedMetadata.get(((OCFile) call.getArgument(1)).getRemotePath()));
        refresh.when(() -> RefreshFolderOperation.prefillLocalFilesMap(any(), any()))
            .thenAnswer(call -> {
                Map<String, OCFile> children = new HashMap<>();
                for (OCFile file : (List<OCFile>) call.getArgument(1)) {
                    children.put(file.getRemotePath(), file);
                }
                return children;
            });
        keep(mockConstruction(ReadFileRemoteOperation.class, (read, construction) -> {
            String path = (String) construction.arguments().get(0);
            when(read.execute(client)).thenAnswer(call -> {
                checks.add(path);
                return result(remote.containsKey(path), List.of(snapshot(remote.get(path))));
            });
        }));
        keep(mockConstruction(ReadFolderRemoteOperation.class, (read, construction) -> {
            String path = (String) construction.arguments().get(0);
            when(read.execute(client)).thenAnswer(call -> {
                listings.add(path);
                List<Object> data = new ArrayList<>();
                data.add(snapshot(remote.get(path)));
                for (OCFile child : children(remote, path)) {
                    data.add(snapshot(child));
                }
                afterListing.run();
                return result(!failedListings.contains(path), data);
            });
        }));
        keep(new FolderSyncIntentMock(queue, recursiveModes, local, queuedModes));
        when(user.getAccountName()).thenReturn("fixture-account");
        when(storage.getFileByPath(anyString())).thenAnswer(call -> local.get(call.getArgument(0)));
        when(storage.getFolderContent(any(OCFile.class), anyBoolean()))
            .thenAnswer(call -> children(local, ((OCFile) call.getArgument(0)).getRemotePath()).stream()
                .map(org.mockito.Mockito::spy).toList());
        doAnswer(call -> {
            OCFile folder = call.getArgument(0);
            folderRowsWritten += 1 + ((List<?>) call.getArgument(1)).size();
            for (OCFile removed : (Collection<OCFile>) call.getArgument(2)) {
                local.keySet().removeIf(path -> path.equals(removed.getRemotePath()) ||
                    (removed.isFolder() && path.startsWith(removed.getRemotePath())));
            }
            for (OCFile file : (List<OCFile>) call.getArgument(1)) {
                if (file.getFileId() == -1) {
                    file.setFileId(nextId++);
                }
                local.put(file.getRemotePath(), FolderSyncRowMock.listingRow(file, local.get(file.getRemotePath())));
            }
            OCFile saved = copy(folder);
            saved.setFileLength(0);
            saved.setFileId(folder.getFileId());
            saved.setStoragePath(local.get(folder.getRemotePath()).getStoragePath());
            saved.setEtagInConflict(local.get(folder.getRemotePath()).getEtagInConflict());
            saved = FolderSyncRowMock.listingRow(saved, local.get(folder.getRemotePath()));
            local.put(folder.getRemotePath(), saved);
            return null;
        }).when(storage).saveFolder(any(), any(), any());
        keep(new FolderSyncRowMock(storage, local));
        addRemote(ROOT, true);
        OCFile root = copy(remote.get(ROOT));
        root.setFileId(nextId++);
        root.setEtag("");
        local.put(ROOT, root);
    }
    void addRemote(String path, boolean folder) {
        OCFile file = new OCFile(path);
        file.setMimeType(folder ? "httpd/unix-directory" : "application/octet-stream");
        file.setEtag("etag-" + remote.size());
        file.setModificationTimestamp(remote.size() + 1);
        remote.put(path, file);
    }
    void populate() {
        for (String branch : List.of("a", "b", "c")) {
            addRemote(ROOT + branch + "/", true);
            addRemote(ROOT + branch + "/deep/", true);
            for (int i = 0; i < 10; i++) {
                addRemote(ROOT + branch + "/deep/file" + i, false);
            }
        }
    }
    void syncTree() { syncTree(true); }
    void syncTree(boolean syncAll) { syncTree(ROOT, syncAll); }
    void syncTree(String root, boolean syncAll) {
        queue.add(root);
        while (!queue.isEmpty()) {
            String path = queue.remove();
            beforeFolder.accept(path);
            Map<String, SynchronizeFileOperation> operations = new HashMap<>();
            for (OCFile server : children(remote, path)) {
                if (!server.isFolder()) {
                    operations.put(server.getRemotePath(), new SynchronizeFileOperation(
                        local.get(server.getRemotePath()), copy(server), user, true, context, storage, false));
                }
            }
            try (MockedConstruction<SynchronizeFileOperation> ignored = mockConstruction(
                SynchronizeFileOperation.class, (operation, construction) -> {
                    OCFile server = (OCFile) construction.arguments().get(1);
                    SynchronizeFileOperation actual = operations.get(server.getRemotePath());
                    Field serverField = SynchronizeFileOperation.class.getDeclaredField("serverFile");
                    serverField.setAccessible(true);
                    serverField.set(actual, server);
                    when(operation.execute(context)).thenAnswer(call -> {
                        fileChecks.add(server.getRemotePath());
                        return actual.execute(client);
                    });
                    when(operation.getLocalFile()).thenAnswer(call -> actual.getLocalFile());
                    when(operation.getTransferWasRequested()).thenAnswer(call -> actual.getTransferWasRequested());
                })) {
                SynchronizeFolderOperation operation =
                    new SynchronizeFolderOperation(context, path, user, storage, false, syncAll);
                operation.setRecursiveChild(!ROOT.equals(path));
                if (queuedModes.containsKey(path)) { operation.setSyncMode(queuedModes.remove(path)); }
                results.add(operation.run(client));
            }
        }
    }
    void resetCounts() {
        downloads.clear();
        uploads.clear();
        listings.clear();
        checks.clear();
        recursiveModes.clear();
        fileChecks.clear();
        results.clear();
        notificationUpdates = 0;
        folderRowsWritten = 0;
    }
    private RemoteFile snapshot(OCFile file) {
        RemoteFile result = mock(RemoteFile.class);
        when(result.getEtag()).thenReturn(file.getEtag());
        snapshots.put(result, copy(file));
        return result;
    }
    private OCFile copy(OCFile file) {
        return FolderSyncFileCopy.copy(file);
    }
    private List<OCFile> children(Map<String, OCFile> source, String folder) {
        List<OCFile> children = new ArrayList<>();
        for (OCFile file : source.values()) {
            String path = file.getRemotePath();
            String relative = path.startsWith(folder) ? path.substring(folder.length()) : "";
            if (relative.endsWith("/")) {
                relative = relative.substring(0, relative.length() - 1);
            }
            if (!relative.isEmpty() && !relative.contains("/")) {
                children.add(file);
            }
        }
        return children;
    }
    private RemoteOperationResult result(boolean success, List<Object> data) {
        RemoteOperationResult result = mock(RemoteOperationResult.class);
        when(result.isSuccess()).thenReturn(success);
        when(result.getCode()).thenReturn(success ? RemoteOperationResult.ResultCode.OK :
            RemoteOperationResult.ResultCode.UNKNOWN_ERROR);
        when(result.getData()).thenReturn(new ArrayList<>(data));
        return result;
    }
    private <T extends AutoCloseable> T keep(T resource) {
        mocks.add(resource);
        return resource;
    }

    private void resetDownloadHelper() throws Exception {
        Field instance = FileDownloadHelper.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Override
    public void close() throws Exception {
        for (int i = mocks.size() - 1; i >= 0; i--) {
            mocks.get(i).close();
        }
        resetDownloadHelper();
    }
}
