/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ext.weaviate.ui;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNEvent;
import org.jkiss.dbeaver.model.navigator.DBNUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.Collection;
import java.util.List;

/**
 * Puts the tree back in step with the server after a collection's tenancy changed.
 * <p>
 * Every path that changes a tenant or a multi-tenancy setting has to do this.
 * {@code resetTenantCache} and {@code refreshConfig} make the model forget, but the navigator
 * holds its own child nodes and keeps drawing them until it is told to re-read.
 * <p>
 * Deliberately <em>not</em> {@code NavigatorHandlerRefresh.refreshNavigator}, which is the
 * obvious call and does not work here. Its de-duplication key is the datasource id, not the node
 * ({@code getRefreshKey}), so every collection under one connection shares a key:
 * <ul>
 *   <li>calling it once per collection drops all but the first, as the later calls find the key
 *       held by the job the first one scheduled;</li>
 *   <li>calling it once with several nodes from the same connection refreshes <em>nothing</em> --
 *       the key loop hits its own second entry, releases what it took and returns early.</li>
 * </ul>
 * It is also asynchronous, so even a single call races whatever the caller does next.
 * <p>
 * Refreshing the nodes directly avoids all three. It runs on the caller's monitor, so the tree is
 * up to date by the time the operation reports success rather than shortly afterwards.
 */
public final class WeaviateNavigatorRefresh {

    private static final Log log = Log.getLog(WeaviateNavigatorRefresh.class);

    private WeaviateNavigatorRefresh() {
    }

    public static void afterTenantChange(
        @NotNull DBRProgressMonitor monitor, @NotNull WeaviateCollection collection
    ) {
        afterTenantChange(monitor, List.of(collection));
    }

    /**
     * Re-reads each collection's node, and the folders under it.
     * <p>
     * The collection is refreshed rather than the Tenants folder alone: a folder is not itself
     * refreshable and walking up from one lands here anyway, while {@code reloadChildren} on the
     * collection recurses into the folders it reuses -- so this reaches the tenant rows and the
     * multi-tenancy settings both.
     * <p>
     * Never throws. This runs after the change has already succeeded, and a tree that failed to
     * repaint is not a reason to report the operation as failed.
     */
    public static void afterTenantChange(
        @NotNull DBRProgressMonitor monitor, @NotNull Collection<WeaviateCollection> collections
    ) {
        for (WeaviateCollection collection : collections) {
            try {
                DBNDatabaseNode node = DBNUtils.getNodeByObject(collection);
                if (node == null) {
                    // Nothing has expanded this collection yet, so there is no stale node to fix.
                    continue;
                }
                node.refreshNode(monitor, DBNEvent.FORCE_REFRESH);
            } catch (Exception e) {
                log.debug("Cannot refresh " + collection.getName() + " after a tenancy change", e);
            }
        }
    }
}
