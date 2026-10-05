/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations;

import com.nextcloud.client.jobs.upload.FileUploadHelper;
import com.owncloud.android.datamodel.OCFile;

import java.lang.reflect.Field;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

final class SyncUploadMock implements AutoCloseable {
    private final Object delegate;
    private final Field value;
    private final Object previousValue;

    SyncUploadMock(List<String> uploads) throws Exception {
        Field lazy = FileUploadHelper.class.getDeclaredField("sharedInstance$delegate");
        lazy.setAccessible(true);
        delegate = lazy.get(null);
        value = delegate.getClass().getDeclaredField("_value");
        value.setAccessible(true);
        previousValue = value.get(delegate);
        FileUploadHelper helper = mock(FileUploadHelper.class);
        doAnswer(call -> {
            uploads.add(((OCFile[]) call.getArgument(1))[0].getRemotePath());
            return null;
        }).when(helper).uploadUpdatedFile(any(), any(), anyInt(), any(), anyBoolean());
        value.set(delegate, helper);
    }

    @Override
    public void close() throws Exception {
        value.set(delegate, previousValue);
    }
}
