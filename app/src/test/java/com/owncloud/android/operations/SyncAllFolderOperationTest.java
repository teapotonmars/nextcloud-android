/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations;

import com.owncloud.android.datamodel.OCFile;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.owncloud.android.operations.FolderSyncFixture.ROOT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;

public class SyncAllFolderOperationTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();
    private FolderSyncFixture fixture;

    @Before
    public void setUp() throws Exception {
        fixture = new FolderSyncFixture(temporaryFolder);
        fixture.populate();
    }

    @After
    public void tearDown() throws Exception {
        fixture.close();
    }

    @Test
    public void initialSyncDownloadsEveryFileAndListsEachFolderOnce() {
        fixture.syncTree();

        assertEquals(30, fixture.downloads.size());
        assertEquals(7, fixture.listings.size());
        assertTrue(fixture.checks.isEmpty());
        assertTrue(fixture.results.stream().allMatch(result -> result.isSuccess()));
    }

    @Test
    public void repeatedUnchangedSyncDoesNotDownloadExistingFiles() {
        warmUp();
        fixture.syncTree();

        assertEquals(7, fixture.listings.size());
        assertTrue(fixture.checks.isEmpty());
        assertEquals(30, fixture.fileChecks.size());
        assertTrue(fixture.downloads.isEmpty());
    }

    @Test
    public void deepAdditionDownloadsOnlyNewFileAndChecksUnchangedSiblingSubtrees() {
        warmUp();
        String added = ROOT + "a/deep/new";
        fixture.addRemote(added, false);
        for (String folder : List.of(ROOT, ROOT + "a/", ROOT + "a/deep/")) {
            fixture.remote.get(folder).setEtag("changed-" + folder);
        }
        fixture.syncTree();

        assertEquals(List.of(added), fixture.downloads);
        assertEquals(7, fixture.listings.size());
        assertTrue(fixture.fileChecks.contains(ROOT + "b/deep/file0"));
        assertTrue(fixture.fileChecks.contains(ROOT + "c/deep/file0"));
        assertEquals(31, fixture.fileChecks.size());
    }

    @Test
    public void additionIsDiscoveredEvenWhenAncestorEtagsAreUnchanged() {
        warmUp();
        String added = ROOT + "a/deep/new";
        fixture.addRemote(added, false);
        fixture.syncTree();

        assertEquals(List.of(added), fixture.downloads);
    }

    @Test
    public void missingNestedLocalFileIsRestoredWithUnchangedRemoteEtags() throws Exception {
        warmUp();
        String missing = ROOT + "b/deep/file0";
        Files.delete(Path.of(fixture.local.get(missing).getStoragePath()));
        fixture.syncTree();

        assertEquals(List.of(missing), fixture.downloads);
        assertTrue(fixture.local.get(missing).isDown());
    }

    @Test
    public void modifiedRemoteFileIsDownloadedAndDeletedFileIsRemoved() {
        warmUp();
        String modified = ROOT + "a/deep/file0";
        String deleted = ROOT + "b/deep/file0";
        fixture.remote.get(modified).setEtag("new-content");
        fixture.remote.remove(deleted);
        fixture.syncTree();

        assertEquals(List.of(modified), fixture.downloads);
        assertEquals("new-content", fixture.local.get(modified).getEtag());
        assertFalse(fixture.local.containsKey(deleted));
        assertEquals(1, fixture.notificationUpdates);
    }

    @Test
    public void failedListingIsRetriedIncludingDescendants() {
        warmUp();
        String added = ROOT + "a/deep/new";
        fixture.addRemote(added, false);
        fixture.failedListings.add(ROOT + "a/");
        fixture.syncTree();

        assertFalse(fixture.local.containsKey(added));
        assertTrue(fixture.results.stream().anyMatch(result -> !result.isSuccess()));
        fixture.failedListings.clear();
        fixture.resetCounts();
        fixture.syncTree();

        assertEquals(List.of(added), fixture.downloads);
        assertEquals(7, fixture.listings.size());
    }

    @Test
    public void failedDownloadIsRetriedEvenAfterFolderMetadataWasSaved() {
        warmUp();
        String modified = ROOT + "a/deep/file0";
        fixture.remote.get(modified).setEtag("new-content");
        fixture.failedDownloads.add(modified);
        fixture.syncTree();

        assertEquals(List.of(modified), fixture.downloads);
        assertNotEquals("new-content", fixture.local.get(modified).getEtag());
        fixture.failedDownloads.clear();
        fixture.resetCounts();
        fixture.syncTree();

        assertEquals(List.of(modified), fixture.downloads);
        assertEquals("new-content", fixture.local.get(modified).getEtag());
    }

    @Test
    public void concurrentChangeDoesNotCacheUnreconciledFolderEtag() {
        warmUp();
        String added = ROOT + "concurrent";
        String listedEtag = fixture.remote.get(ROOT).getEtag();
        fixture.afterListing = () -> {
            fixture.afterListing = () -> { };
            fixture.addRemote(added, false);
            fixture.remote.get(ROOT).setEtag("concurrent-state");
        };
        fixture.syncTree();

        assertFalse(fixture.local.containsKey(added));
        assertEquals(listedEtag, fixture.local.get(ROOT).getEtag());
        fixture.resetCounts();
        fixture.syncTree();

        assertEquals(List.of(added), fixture.downloads);
    }

    @Test
    public void cancellationBeforeDiscoveryLeavesCacheUntouchedAndRetryWorks() {
        String originalEtag = fixture.local.get(ROOT).getEtag();
        SynchronizeFolderOperation operation = new SynchronizeFolderOperation(
            fixture.context, ROOT, fixture.user, fixture.storage, false, true);
        operation.cancel();

        assertFalse(operation.run(fixture.client).isSuccess());
        assertTrue(fixture.listings.isEmpty());
        assertEquals(originalEtag, fixture.local.get(ROOT).getEtag());
        fixture.syncTree();
        assertEquals(30, fixture.downloads.size());
    }

    @Test
    public void absentLocalEtagUsesModificationTimeWithoutRedownloading() {
        warmUp();
        OCFile local = fixture.local.get(ROOT + "a/deep/file0");
        local.setEtag("");
        fixture.syncTree();

        assertTrue(fixture.downloads.isEmpty());
        fixture.remote.get(local.getRemotePath()).setModificationTimestamp(
            local.getModificationTimestampAtLastSyncForData() + 1);
        fixture.resetCounts();
        fixture.syncTree();
        assertEquals(List.of(local.getRemotePath()), fixture.downloads);
    }

    @Test
    public void localEditIsUploadedWithoutDownloading() {
        warmUp();
        OCFile local = fixture.local.get(ROOT + "b/deep/file0");
        assertTrue(new java.io.File(local.getStoragePath()).setLastModified(local.getLastSyncDateForData() + 5000));
        fixture.syncTree();

        assertEquals(List.of(local.getRemotePath()), fixture.uploads);
        assertTrue(fixture.downloads.isEmpty());
    }

    @Test
    public void concurrentLocalAndRemoteEditsRemainAConflict() {
        warmUp();
        OCFile local = fixture.local.get(ROOT + "b/deep/file0");
        assertTrue(new java.io.File(local.getStoragePath()).setLastModified(local.getLastSyncDateForData() + 5000));
        fixture.remote.get(local.getRemotePath()).setEtag("conflicting-content");
        fixture.syncTree();

        verify(fixture.storage).saveConflict(local, "conflicting-content");
        assertTrue(fixture.downloads.isEmpty());
        assertTrue(fixture.uploads.isEmpty());
    }

    private void warmUp() {
        fixture.syncTree();
        assertEquals(30, fixture.downloads.size());
        fixture.resetCounts();
    }

    @Test
    public void ordinaryUnchangedFolderSyncUsesOnlyEtagCheck() {
        warmUp();
        SynchronizeFolderOperation operation = new SynchronizeFolderOperation(
            fixture.context, ROOT, fixture.user, fixture.storage, false, false);
        assertTrue(operation.run(fixture.client).isSuccess());
        assertEquals(List.of(ROOT), fixture.checks);
        assertTrue(fixture.listings.isEmpty());
    }

    @Test
    public void ordinaryChangedFolderSyncCachesItsListingEtag() {
        warmUp();
        String added = ROOT + "new-folder/";
        fixture.addRemote(added, true);
        fixture.remote.get(ROOT).setEtag("new-folder-etag");
        SynchronizeFolderOperation operation = new SynchronizeFolderOperation(
            fixture.context, ROOT, fixture.user, fixture.storage, false, false);
        assertTrue(operation.run(fixture.client).isSuccess());
        assertEquals(List.of(ROOT), fixture.checks);
        assertEquals(List.of(ROOT), fixture.listings);
        assertTrue(fixture.local.containsKey(added));
        assertEquals("new-folder-etag", fixture.local.get(ROOT).getEtag());
    }

    @Test
    public void cancellationDuringDiscoveryLeavesCacheUntouchedAndRetryWorks() {
        String originalEtag = fixture.local.get(ROOT).getEtag();
        SynchronizeFolderOperation operation = new SynchronizeFolderOperation(
            fixture.context, ROOT, fixture.user, fixture.storage, false, true);
        fixture.afterListing = operation::cancel;
        assertFalse(operation.run(fixture.client).isSuccess());
        assertEquals(originalEtag, fixture.local.get(ROOT).getEtag());
        fixture.afterListing = () -> { };
        fixture.syncTree();
        assertEquals(30, fixture.downloads.size());
    }

    @Test
    public void requestCountsForControlledHierarchy() {
        fixture.syncTree();
        printCounts("initial");
        org.mockito.Mockito.clearInvocations(fixture.storage);
        assertEquals(30, fixture.downloads.size());
        fixture.resetCounts();
        fixture.syncTree();
        printCounts("unchanged");
        assertTrue(fixture.downloads.isEmpty());
        assertEquals(0, fixture.notificationUpdates);
        verify(fixture.storage, org.mockito.Mockito.never()).saveConflict(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.isNull());
        fixture.resetCounts();
        String added = ROOT + "a/deep/new";
        fixture.addRemote(added, false);
        fixture.syncTree();
        printCounts("deep addition");
        assertEquals(List.of(added), fixture.downloads);
        assertEquals(1, fixture.notificationUpdates);
    }

    private void printCounts(String scenario) {
        System.out.println(scenario + ": depth0=" + fixture.checks.size() +
            ", depth1=" + fixture.listings.size() + ", fileChecks=" + fixture.fileChecks.size() +
            ", downloads=" + fixture.downloads.size() + ", notifications=" + fixture.notificationUpdates +
            ", folderRowsWritten=" + fixture.folderRowsWritten);
    }
}
