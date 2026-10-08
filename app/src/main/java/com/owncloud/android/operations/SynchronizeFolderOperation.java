/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2020 Chris Narkiewicz <hello@ezaquarii.com>
 * SPDX-FileCopyrightText: 2018-2023 Tobias Kaminsky <tobias@kaminsky.me>
 * SPDX-FileCopyrightText: 2018 Andy Scherzinger <info@andy-scherzinger.de>
 * SPDX-FileCopyrightText: 2016 ownCloud Inc.
 * SPDX-FileCopyrightText: 2012-2013 David A. Velasco <dvelasco@solidgear.es>
 * SPDX-License-Identifier: GPL-2.0-only AND (AGPL-3.0-or-later OR GPL-2.0-only)
 */
package com.owncloud.android.operations;

import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import com.nextcloud.client.account.User;
import com.nextcloud.utils.share.UnifiedShareSharees;
import com.nextcloud.client.jobs.download.FileDownloadHelper;
import com.nextcloud.client.jobs.folderDownload.FolderDownloadWorkerNotificationManager;
import com.nextcloud.utils.extensions.ExtensionsKt;
import com.owncloud.android.datamodel.FileDataStorageManager;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.datamodel.FolderSyncSnapshot;
import com.owncloud.android.datamodel.e2e.v1.decrypted.DecryptedFolderMetadataFileV1;
import com.owncloud.android.datamodel.e2e.v2.decrypted.DecryptedFolderMetadataFile;
import com.owncloud.android.lib.common.OwnCloudClient;
import com.owncloud.android.lib.common.operations.OperationCancelledException;
import com.owncloud.android.lib.common.operations.RemoteOperationResult;
import com.owncloud.android.lib.common.operations.RemoteOperationResult.ResultCode;
import com.owncloud.android.lib.common.utils.Log_OC;
import com.owncloud.android.lib.resources.files.ReadFileRemoteOperation;
import com.owncloud.android.lib.resources.files.ReadFolderRemoteOperation;
import com.owncloud.android.lib.resources.files.model.RemoteFile;
import com.owncloud.android.operations.common.SyncOperation;
import com.owncloud.android.services.OperationsService;
import com.owncloud.android.utils.FileStorageUtils;
import com.owncloud.android.utils.MimeTypeUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Vector;
import java.util.concurrent.atomic.AtomicBoolean;

import kotlin.Unit;

/**
 *  Remote operation performing the synchronization of the list of files contained
 *  in a folder identified with its remote path.
 *  Fetches the list and properties of the files contained in the given folder, including their
 *  properties, and updates the local database with them.
 *  Does NOT enter in the child folders to synchronize their contents also, BUT requests for a new operation instance
 *  doing so.
 */
public class SynchronizeFolderOperation extends SyncOperation {

    private static final String TAG = SynchronizeFolderOperation.class.getSimpleName();

    /** Remote path of the folder to synchronize */
    private String mRemotePath;

    /** Account where the file to synchronize belongs */
    private User user;

    /** Android context; necessary to send requests to the download service */
    private Context mContext;

    /** Locally cached information about folder to synchronize */
    private OCFile mLocalFolder;

    /** Counter of conflicts found between local and remote files */
    private int mConflictsFound;

    /** Counter of failed operations in synchronization of kept-in-sync files */
    private int mFailsInFileSyncsFound;

    /**
     * 'True' means that the remote folder changed and should be fetched
     */
    private boolean mRemoteFolderChanged;

    private List<OCFile> mFilesForDirectDownload;
    // to avoid extra PROPFINDs when there was no change in the folder

    private List<SynchronizeFileOperation> mFilesToSyncContents;
    // this will be used for every file when 'folder synchronization' replaces 'folder download'

    private final AtomicBoolean mCancellationRequested;

    private final boolean useWorkerWithNotification;

    private volatile FolderSyncMode syncMode;

    private volatile boolean recursiveChild;

    final FolderDownloadWorkerNotificationManager notificationManager;

    /**
     * Creates a new instance of {@link SynchronizeFolderOperation}.
     *
     * @param context         Application context.
     * @param remotePath      Path to synchronize.
     * @param user            Nextcloud account where the folder is located.
     */
    public SynchronizeFolderOperation(Context context,
                                      String remotePath,
                                      User user,
                                      FileDataStorageManager storageManager,
                                      boolean useWorkerWithNotification,
                                      boolean syncAll) {
        super(storageManager);

        mRemotePath = remotePath;
        this.user = user;
        mContext = context;
        mRemoteFolderChanged = false;
        mFilesForDirectDownload = new Vector<>();
        mFilesToSyncContents = new Vector<>();
        mCancellationRequested = new AtomicBoolean(false);
        this.useWorkerWithNotification = useWorkerWithNotification;
        syncMode = syncAll ? FolderSyncMode.RECURSIVE_CACHED : FolderSyncMode.SINGLE_FOLDER;
        notificationManager = new FolderDownloadWorkerNotificationManager(context, false,null);
    }


    /**
     * Performs the synchronization.
     *
     * {@inheritDoc}
     */
    @Override
    protected RemoteOperationResult run(OwnCloudClient client) {
        return FolderSyncExecutionLock.execute(user.getAccountName(), mCancellationRequested::get,
            () -> synchronizeFolder(client));
    }

    private RemoteOperationResult synchronizeFolder(OwnCloudClient client) {
        RemoteOperationResult result;
        mFailsInFileSyncsFound = 0;
        mConflictsFound = 0;

        try {
            // get locally cached information about folder
            mLocalFolder = getStorageManager().getFileByPath(mRemotePath);
            if (mLocalFolder == null) {
                Log_OC.e(TAG, "Local folder is null, cannot run synchronize folder operation, remote path: " + mRemotePath);
                return new RemoteOperationResult<>(ResultCode.FILE_NOT_FOUND);
            }

            if (syncMode.isRecursive()) {
                result = synchronizeRecursiveInventory(client);
            } else {
                result = checkForChanges(client);
                if (result.isSuccess() && mRemoteFolderChanged) {
                    result = fetchAndSyncRemoteFolder(client);
                } else if (result.isSuccess()) {
                    prepareOpsFromLocalKnowledge();
                }
            }

            if (result.isSuccess()) {
                syncContents();
                if (syncMode.isRecursive()) {
                    getStorageManager().updateFolderSyncTime(mLocalFolder, System.currentTimeMillis());
                }
                if (mLocalFolder.isInConflict()) {
                    // A prior interrupted conflict cleanup may have left only the folder marker.
                    getStorageManager().clearFolderConflictIfResolved(mLocalFolder);
                }
            }

            if (mCancellationRequested.get()) {
                throw new OperationCancelledException();
            }

        } catch (OperationCancelledException e) {
            result = new RemoteOperationResult(e);
        }

        return result;
    }

    public void setRecursiveChild(boolean recursiveChild) {
        this.recursiveChild = recursiveChild;
    }

    public FolderSyncMode getSyncMode() {
        return syncMode;
    }

    public void setSyncMode(FolderSyncMode syncMode) {
        this.syncMode = syncMode;
    }

    private RemoteOperationResult synchronizeRecursiveInventory(OwnCloudClient client)
        throws OperationCancelledException {
        if (mCancellationRequested.get()) {
            throw new OperationCancelledException();
        }
        if (syncMode.isForced()) {
            return fetchAndSyncRemoteFolder(client);
        }
        List<OCFile> children = getStorageManager().getFolderContent(mLocalFolder, false);
        FolderSyncSnapshot snapshot;
        try {
            snapshot = FolderSyncSnapshot.decode(getStorageManager().getFolderSyncSnapshot(mRemotePath));
        } catch (RuntimeException exception) {
            Log_OC.e(TAG, "Could not read directory inventory certificate", exception);
            return fetchAndSyncRemoteFolder(client);
        }
        if (snapshot == null || !snapshot.matches(mLocalFolder, children, System.currentTimeMillis())) {
            return fetchAndSyncRemoteFolder(client);
        }
        String remoteEtag = recursiveChild ? mLocalFolder.getEtagOnServer() : null;
        if (!recursiveChild) {
            ReadFileRemoteOperation operation = new ReadFileRemoteOperation(mRemotePath);
            var result = operation.execute(client);
            if (!result.isSuccess()) {
                if (result.getCode() == ResultCode.FILE_NOT_FOUND) {
                    removeLocalFolder();
                }
                return result;
            }
            OCFile remoteFolder = FileStorageUtils.fillOCFile((RemoteFile) result.getData().get(0));
            if (!FolderSyncSnapshot.eligible(remoteFolder, children) ||
                !java.util.Objects.equals(remoteFolder.getPermissions(), mLocalFolder.getPermissions())) {
                return fetchAndSyncRemoteFolder(client);
            }
            remoteEtag = remoteFolder.getEtag();
        }
        if (!snapshot.getEtag().equals(remoteEtag)) {
            return fetchAndSyncRemoteFolder(client);
        }
        Map<Long, OCFile> localFiles = new HashMap<>();
        if (children.stream().anyMatch(child -> !child.isFolder())) {
            for (OCFile localFile : getStorageManager().getFolderContent(mLocalFolder, false)) {
                localFiles.put(localFile.getFileId(), localFile);
            }
            if (children.stream().anyMatch(child -> !child.isFolder() && !localFiles.containsKey(child.getFileId()))) {
                return fetchAndSyncRemoteFolder(client);
            }
        }
        for (OCFile child : children) {
            if (mCancellationRequested.get()) {
                throw new OperationCancelledException();
            }
            if (child.isFolder()) {
                startSyncFolderOperation(child.getRemotePath());
            } else {
                OCFile localFile = localFiles.get(child.getFileId());
                child.setEtag(child.getEtagOnServer());
                prepareFileSync(child, localFile);
            }
        }
        return new RemoteOperationResult<>(ResultCode.OK);
    }

    private RemoteOperationResult checkForChanges(OwnCloudClient client) throws OperationCancelledException {
        Log_OC.d(TAG, "Checking changes in " + user.getAccountName() + mRemotePath);

        mRemoteFolderChanged = true;

        if (mCancellationRequested.get()) {
            throw new OperationCancelledException();
        }

        // remote request
        ReadFileRemoteOperation operation = new ReadFileRemoteOperation(mRemotePath);
        var result = operation.execute(client);
        if (result.isSuccess() && result.getData().get(0) instanceof RemoteFile remoteFile) {
            OCFile remoteFolder = FileStorageUtils.fillOCFile(remoteFile);

            // check if remote and local folder are different
            mRemoteFolderChanged = !(remoteFolder.getEtag().equalsIgnoreCase(mLocalFolder.getEtag()));

            result = new RemoteOperationResult<>(ResultCode.OK);

            Log_OC.i(TAG, "Checked " + user.getAccountName() + mRemotePath + " : " +
                (mRemoteFolderChanged ? "changed" : "not changed"));
        } else {
            // check failed
            if (result.getCode() == ResultCode.FILE_NOT_FOUND) {
                removeLocalFolder();
            }
            if (result.isException()) {
                Log_OC.e(TAG, "Checked " + user.getAccountName() + mRemotePath  + " : " +
                        result.getLogMessage(), result.getException());
            } else {
                Log_OC.e(TAG, "Checked " + user.getAccountName() + mRemotePath + " : " +
                        result.getLogMessage());
            }

        }

        return result;
    }


    private RemoteOperationResult fetchAndSyncRemoteFolder(OwnCloudClient client) throws OperationCancelledException {
        if (mCancellationRequested.get()) {
            throw new OperationCancelledException();
        }

        ReadFolderRemoteOperation operation = new ReadFolderRemoteOperation(mRemotePath);
        var result = operation.execute(client);
        Log_OC.d(TAG, "Synchronizing " + user.getAccountName() + mRemotePath);
        Log_OC.d(TAG, "Synchronizing remote id" + mLocalFolder.getRemoteId());

        if (result.isSuccess()) {
            synchronizeData(result.getData());
            if (mConflictsFound > 0  || mFailsInFileSyncsFound > 0) {
                result = new RemoteOperationResult<>(ResultCode.SYNC_CONFLICT);
                    // should be a different result code, but will do the job
            }
        } else {
            if (result.getCode() == ResultCode.FILE_NOT_FOUND) {
                removeLocalFolder();
            }
        }

        return result;
    }


    private void removeLocalFolder() {
        FileDataStorageManager storageManager = getStorageManager();
        if (storageManager.fileExists(mLocalFolder.getFileId())) {
            String currentSavePath = FileStorageUtils.getSavePath(user.getAccountName());
            storageManager.removeFolder(
                    mLocalFolder,
                    true,
                    mLocalFolder.isDown() // TODO: debug, I think this is always false for folders
                            && mLocalFolder.getStoragePath().startsWith(currentSavePath)
            );
        }
    }


    /**
     * Synchronizes the data retrieved from the server about the contents of the target folder
     * with the current data in the local database.
     *
     * @param folderAndFiles Remote folder and children files in Folder
     */
    private void synchronizeData(List<Object> folderAndFiles) throws OperationCancelledException {


        // parse data from remote folder
        OCFile remoteFolder = FileStorageUtils.fillOCFile((RemoteFile) folderAndFiles.get(0));
        remoteFolder.setParentId(mLocalFolder.getParentId());
        remoteFolder.setFileId(mLocalFolder.getFileId());
        boolean remoteFolderChanged = !remoteFolder.getEtag().equals(mLocalFolder.getEtag());

        Log_OC.d(TAG, "Remote folder " + mLocalFolder.getRemotePath() + " changed - starting update of local data ");

        mFilesForDirectDownload.clear();
        mFilesToSyncContents.clear();

        if (mCancellationRequested.get()) {
            throw new OperationCancelledException();
        }

        FileDataStorageManager storageManager = getStorageManager();

        // if local folder is encrypted, download fresh metadata
        boolean encryptedAncestor = FileStorageUtils.checkEncryptionStatus(remoteFolder, storageManager);
        mLocalFolder.setEncrypted(encryptedAncestor);

        // update permission
        mLocalFolder.setPermissions(remoteFolder.getPermissions());

        // update richWorkspace
        mLocalFolder.setRichWorkspace(remoteFolder.getRichWorkspace());

        Object object = RefreshFolderOperation.getDecryptedFolderMetadata(encryptedAncestor,
                                                                                                 mLocalFolder,
                                                                                                 getClient(),
                                                                                                 user,
                                                                                                 mContext);
        if (mLocalFolder.isEncrypted() && object == null) {
            throw new IllegalStateException("metadata is null!");
        }

        // get current data about local contents of the folder to synchronize
        Map<String, OCFile> localFilesMap = RefreshFolderOperation.prefillLocalFilesMap(object,storageManager.getFolderContent(mLocalFolder, false));

        // loop to synchronize every child
        List<OCFile> updatedFiles = new ArrayList<>(folderAndFiles.size() - 1);
        List<OCFile> foldersToSync = new ArrayList<>();
        OCFile remoteFile;
        OCFile localFile;
        OCFile updatedFile;
        RemoteFile remote;

        for (int i = 1; i < folderAndFiles.size(); i++) {
            /// new OCFile instance with the data from the server
            remote = (RemoteFile) folderAndFiles.get(i);
            remoteFile = FileStorageUtils.fillOCFile(remote);

            /// new OCFile instance to merge fresh data from server with local state
            updatedFile = FileStorageUtils.fillOCFile(remote);
            updatedFile.setParentId(mLocalFolder.getFileId());

            /// retrieve local data for the read file
            localFile = localFilesMap.remove(remoteFile.getRemotePath());

            // TODO better implementation is needed
            if (localFile == null) {
                localFile = storageManager.getFileByPath(updatedFile.getRemotePath());
            }

            /// add to updatedFile data about LOCAL STATE (not existing in server)
            updateLocalStateData(remoteFile, localFile, updatedFile, remoteFolderChanged);

            /// check and fix, if needed, local storage path
            FileStorageUtils.searchForLocalFileInDefaultPath(updatedFile, user.getAccountName());

            // update file name for encrypted files
            if (object instanceof DecryptedFolderMetadataFileV1 metadataFile) {
                RefreshFolderOperation.updateFileNameForEncryptedFileV1(storageManager, metadataFile, updatedFile);
            } else if (object instanceof DecryptedFolderMetadataFile metadataFile) {
                RefreshFolderOperation.updateFileNameForEncryptedFile(storageManager, metadataFile, updatedFile);
            }

            // we parse content, so either the folder itself or its direct parent (which we check) must be encrypted
            boolean encrypted = updatedFile.isEncrypted() || mLocalFolder.isEncrypted();
            updatedFile.setEncrypted(encrypted);

            if (remoteFile.isFolder()) {
                foldersToSync.add(remoteFile);
            } else {
                prepareFileSync(remoteFile, localFile);
            }

            updatedFiles.add(updatedFile);
        }

        // update file name for encrypted files
        if (object instanceof DecryptedFolderMetadataFileV1 metadataFile) {
            RefreshFolderOperation.updateFileNameForEncryptedFileV1(storageManager, metadataFile, mLocalFolder);
        } else if (object instanceof DecryptedFolderMetadataFile metadataFile) {
            RefreshFolderOperation.updateFileNameForEncryptedFile(storageManager, metadataFile, mLocalFolder);
        }

        // save updated contents in local database
        UnifiedShareSharees.fillBlocking(user, updatedFiles);

        storageManager.saveSynchronizedFolder(remoteFolder, mLocalFolder, updatedFiles, localFilesMap.values());
        OCFile savedFolder = storageManager.getFileByPath(mRemotePath);
        if (savedFolder != null) {
            mLocalFolder = savedFolder;
        }
        if (!syncMode.isRecursive()) {
            storageManager.updateFolderSyncTime(mLocalFolder, System.currentTimeMillis());
        }
        if (syncMode.isRecursive() && FolderSyncSnapshot.eligible(mLocalFolder, updatedFiles)) {
            String inventory = FolderSyncSnapshot.fingerprint(mLocalFolder, updatedFiles);
            List<OCFile> savedChildren = storageManager.getFolderContent(mLocalFolder, false);
            if (inventory.equals(FolderSyncSnapshot.fingerprint(mLocalFolder, savedChildren))) {
                // This certifies only the immediate inventory; every child must validate its own certificate.
                try {
                    storageManager.saveFolderSyncSnapshot(mRemotePath,
                        new FolderSyncSnapshot(remoteFolder.getEtag(), inventory, System.currentTimeMillis()).encode());
                } catch (RuntimeException exception) {
                    Log_OC.e(TAG, "Could not save directory inventory certificate", exception);
                }
            }
        }
        // Queued operations can start immediately and require the committed child metadata.
        for (OCFile folder : foldersToSync) {
            if (mCancellationRequested.get()) {
                throw new OperationCancelledException();
            }
            startSyncFolderOperation(folder.getRemotePath());
        }
    }

    private static void updateLocalStateData(OCFile remoteFile,
                                             OCFile localFile,
                                             OCFile updatedFile,
                                             boolean remoteFolderChanged) {
        updatedFile.setLastSyncDateForProperties(System.currentTimeMillis());
        updatedFile.setEtagOnServer(remoteFile.getEtag());
        if (localFile != null) {
            updatedFile.setFileId(localFile.getFileId());
            updatedFile.setLastSyncDateForData(localFile.getLastSyncDateForData());
            updatedFile.setModificationTimestampAtLastSyncForData(
                    localFile.getModificationTimestampAtLastSyncForData()
            );
            updatedFile.setStoragePath(localFile.getStoragePath());
            // eTag will not be updated unless file CONTENTS are synchronized
            updatedFile.setEtag(localFile.getEtag());
            if (updatedFile.isFolder()) {
                updatedFile.setFileLength(localFile.getFileLength());
                    // TODO move operations about size of folders to FileContentProvider
            } else if (remoteFolderChanged && MimeTypeUtil.isImage(remoteFile) &&
                    remoteFile.getModificationTimestamp() !=
                            localFile.getModificationTimestamp()) {
                updatedFile.setUpdateThumbnailNeeded(true);
                Log_OC.d(TAG, "Image " + remoteFile.getFileName() + " updated on the server");
            }
            updatedFile.setSharedViaLink(localFile.isSharedViaLink());
            updatedFile.setSharedWithSharee(localFile.isSharedWithSharee());
            updatedFile.setEtagInConflict(localFile.getEtagInConflict());
        } else {
            // remote eTag will not be updated unless file CONTENTS are synchronized
            updatedFile.setEtag("");
        }
    }

    private void prepareFileSync(OCFile remoteFile, OCFile localFile) {
        SynchronizeFileOperation operation = new SynchronizeFileOperation(
            localFile,
            remoteFile,
            user,
            true,
            mContext,
            getStorageManager(),
            useWorkerWithNotification
        );
        mFilesToSyncContents.add(operation);
    }

    private void prepareOpsFromLocalKnowledge() throws OperationCancelledException {
        List<OCFile> children = getStorageManager().getFolderContent(mLocalFolder, false);
        for (OCFile child : children) {
            if (!child.isFolder()) {
                if (!child.isDown()) {
                    mFilesForDirectDownload.add(child);
                } else {
                    /// this should result in direct upload of files that were locally modified
                    SynchronizeFileOperation operation = new SynchronizeFileOperation(
                        child,
                        child.getEtagInConflict() != null ? child : null,
                        user,
                        true,
                        mContext,
                        getStorageManager(),
                        useWorkerWithNotification
                    );
                    mFilesToSyncContents.add(operation);
                }
            }
        }
    }

    private void syncContents() throws OperationCancelledException {
        startDirectDownloads();
        startContentSynchronizations(mFilesToSyncContents);
    }

    private void startDirectDownloads() {
        final var fileDownloadHelper = FileDownloadHelper.Companion.instance();

        if (useWorkerWithNotification) {
            fileDownloadHelper.downloadFolder(mLocalFolder, user.getAccountName());
        } else {
            try {
                for (OCFile file: mFilesForDirectDownload) {
                    synchronized (mCancellationRequested) {
                        if (mCancellationRequested.get()) {
                            break;
                        }
                    }

                    if (file == null) {
                        continue;
                    }

                    final var operation = new DownloadFileOperation(user, file, mContext);
                    var result = operation.execute(getClient());

                    String filename = file.getFileName();
                    if (filename == null) {
                        continue;
                    }

                    if (result.isSuccess()) {
                        fileDownloadHelper.saveFile(file, operation, getStorageManager());
                        Log_OC.d(TAG, "startDirectDownloads completed for: " + file.getFileName());
                    } else {
                        Log_OC.d(TAG, "startDirectDownloads failed for: " + file.getFileName());
                    }
                }
            } catch (Exception e) {
                Log_OC.d(TAG, "Exception caught at startDirectDownloads" + e);
            }
        }
    }


    /**
     * Performs a list of synchronization operations, determining if a download or upload is needed
     * or if exists conflict due to changes both in local and remote contents of the each file.
     * <p>
     * If download or upload is needed, request the operation to the corresponding service and goes on.
     *
     * @param filesToSyncContents       Synchronization operations to execute.
     */
    private void startContentSynchronizations(List<SynchronizeFileOperation> filesToSyncContents) throws OperationCancelledException {
        Log_OC.v(TAG, "Starting content synchronization... ");

        int total = filesToSyncContents.size();
        String folderName = mLocalFolder.getFileName();

        for (int current = 0; current < filesToSyncContents.size(); current++) {
            if (mCancellationRequested.get()) {
                throw new OperationCancelledException();
            }

            final var synchronizeFileOperation = filesToSyncContents.get(current);
            final var result = synchronizeFileOperation.execute(mContext);
            final var file = synchronizeFileOperation.getLocalFile();

            if (result.isSuccess() && file != null) {
                if (synchronizeFileOperation.getTransferWasRequested()) {
                    notificationManager.showProgressNotification(folderName, file.getFileName(), current, total);
                }
            } else {
                if (result.getCode() == ResultCode.SYNC_CONFLICT) {
                    mConflictsFound++;
                } else {
                    mFailsInFileSyncsFound++;

                    String message = "Error while synchronizing file : ";

                    if (result.getException() != null) {
                        message = message + result.getLogMessage() + " Exception: "
                            + result.getException().getMessage();
                    } else {
                        message = message + result.getLogMessage();
                    }

                    Log_OC.e(TAG, message);
                }
            }
        }

        ExtensionsKt.mainThread(0L, () -> {
            notificationManager.dismiss();
            return Unit.INSTANCE;
        });
    }


    /**
     * Cancel operation
     */
    public void cancel() {
        mCancellationRequested.set(true);
    }

    public Optional<String> getFolderNameFromPath() {
        if (mLocalFolder == null) {
            return Optional.empty();
        }

        String path = mLocalFolder.getStoragePath();
        if (!TextUtils.isEmpty(path)) {
            File folder = new File(path);
            return Optional.of(folder.getName());
        }

        String filepath = FileStorageUtils.getDefaultSavePathFor(user.getAccountName(), mLocalFolder);
        File folder = new File(filepath);
        return Optional.of(folder.getName());
    }

    private void startSyncFolderOperation(String path) {
        Intent intent = new Intent(mContext, OperationsService.class);
        intent.setAction(OperationsService.ACTION_SYNC_FOLDER);
        intent.putExtra(OperationsService.EXTRA_ACCOUNT, user.toPlatformAccount());
        intent.putExtra(OperationsService.EXTRA_REMOTE_PATH, path);
        intent.putExtra(OperationsService.EXTRA_SYNC_ALL, syncMode.isRecursive());
        intent.putExtra(OperationsService.EXTRA_SYNC_FOLDER_RECURSIVE_CHILD, syncMode.isRecursive());
        intent.putExtra(OperationsService.EXTRA_FORCE_LISTING, syncMode.isForced());
        mContext.startService(intent);
    }

    public String getRemotePath() {
        return mRemotePath;
    }

    public String getAccountName() {
        return user.getAccountName();
    }

    public Long getFolderId() {
        if (mLocalFolder == null) {
            return null;
        }

        return mLocalFolder.getFileId();
    }
}
