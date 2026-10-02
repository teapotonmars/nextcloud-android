/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.owncloud.android.operations;

import android.content.Intent;
import com.owncloud.android.datamodel.OCFile;
import com.owncloud.android.services.OperationsService;
import org.mockito.MockedConstruction;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

final class FolderSyncIntentMock implements AutoCloseable {
    private final MockedConstruction<Intent> intents;

    FolderSyncIntentMock(ArrayDeque<String> queue, List<Boolean> modes, Map<String, OCFile> local) {
        intents = mockConstruction(Intent.class, (intent, ignored) -> {
            String[] path = new String[1];
            when(intent.putExtra(eq(OperationsService.EXTRA_SYNC_ALL), anyBoolean())).thenAnswer(call -> {
                modes.add(call.getArgument(1));
                return intent;
            });
            when(intent.putExtra(eq(OperationsService.EXTRA_REMOTE_PATH), anyString())).thenAnswer(call -> {
                path[0] = call.getArgument(1);
                org.junit.Assert.assertTrue("Child queued before save: " + path[0], local.containsKey(path[0]));
                queue.add(path[0]);
                return intent;
            });
        });
    }

    @Override
    public void close() {
        intents.close();
    }
}
