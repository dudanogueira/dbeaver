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
import org.jkiss.dbeaver.model.navigator.DBNUtils;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerRefresh;

import java.util.List;

/**
 * Puts the tree back in step with the server after a tenant changed.
 * <p>
 * Every path that flips a tenant's state -- the Manage Tenants dialog, the Activate button in the
 * Query panel, the action on a tenant node -- has to do this. {@code resetTenantCache} makes the
 * model forget, but the navigator holds its own child nodes and keeps drawing them until it is
 * told to re-read; without this the tree goes on showing a tenant as inactive after it was woken,
 * and the only way back is a manual refresh.
 */
public final class WeaviateNavigatorRefresh {

    private static final Log log = Log.getLog(WeaviateNavigatorRefresh.class);

    private WeaviateNavigatorRefresh() {
    }

    /**
     * Re-reads the collection's node, and with it the Tenants folder underneath.
     * <p>
     * The collection is refreshed rather than the Tenants folder alone. Finding that folder means
     * walking children that may not be loaded, and re-reading one collection is cheap -- the
     * tenant list is a single request, and everything else under it is already in memory.
     * <p>
     * Never throws: this runs after the change has already succeeded, and a tree that failed to
     * repaint is not a reason to report the operation as failed.
     */
    public static void afterTenantChange(@NotNull WeaviateCollection collection) {
        try {
            DBNDatabaseNode node = DBNUtils.getNodeByObject(collection);
            if (node != null) {
                NavigatorHandlerRefresh.refreshNavigator(List.of(node));
            }
        } catch (Exception e) {
            log.debug("Cannot refresh the navigator after a tenant change", e);
        }
    }
}
