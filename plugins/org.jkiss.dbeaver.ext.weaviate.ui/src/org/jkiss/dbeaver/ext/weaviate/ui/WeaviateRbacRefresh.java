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
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNEvent;
import org.jkiss.dbeaver.model.navigator.DBNUtils;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

/**
 * Puts the Security branch back in step with the server after a change.
 * <p>
 * Same shape and the same reasoning as {@link WeaviateNavigatorRefresh}: refresh the node
 * directly rather than through {@code NavigatorHandlerRefresh}, whose de-duplication key is the
 * datasource id, so two calls for one connection cancel each other out and a single call races
 * whatever the caller does next.
 * <p>
 * Refreshing the connection node is safe here -- {@code WeaviateDataSource} is a
 * {@code DBPRefreshableObject}, so this re-reads rather than reconnecting, which it used to do.
 */
public final class WeaviateRbacRefresh {

    private static final Log log = Log.getLog(WeaviateRbacRefresh.class);

    private WeaviateRbacRefresh() {
    }

    /**
     * Never throws. This runs after a change the server has already accepted, and a tree that
     * failed to repaint is not a reason to report the change as failed.
     */
    public static void after(
        @NotNull DBRProgressMonitor monitor, @NotNull WeaviateDataSource dataSource
    ) {
        try {
            dataSource.resetRbacCache();
            DBNDatabaseNode node = DBNUtils.getNodeByObject(dataSource.getContainer());
            if (node != null) {
                node.refreshNode(monitor, DBNEvent.FORCE_REFRESH);
            }
        } catch (Exception e) {
            log.debug("Cannot refresh the security nodes", e);
        }
    }
}
