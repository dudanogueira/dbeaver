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
package org.jkiss.dbeaver.ext.weaviate.ui.handlers;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateShardGroup;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.navigator.DBNUtils;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen;

import java.lang.reflect.InvocationTargetException;

/**
 * Opens a collection's data from the Shards branch, on double-click.
 * <p>
 * Under Cluster Nodes a node's shards are grouped by collection, and that grouping row is the one
 * thing in that branch someone is likely to want to act on: they have found where a collection
 * lives and now want to look at what is in it. Double-clicking it used to open a properties editor
 * for the shard group itself, which is a row of counts nobody opened it for.
 * <p>
 * <b>Reached by a tree-meta handler, not by overriding the platform's open command.</b> The
 * navigator's double-click is hard-coded in {@code NavigatorViewBase} and ends at
 * {@code NavigatorUtils.executeNodeAction(Action.open, node, ...)}, which asks the node's own
 * {@code DBXTreeNode} meta whether it has a handler for that action before falling back to the
 * default. So declaring {@code <handler action="open" command="..."/>} on the shard-group
 * {@code <items>} redirects the double-click for that row alone -- no second handler competing for
 * the platform's command, and no conflict for Eclipse to resolve arbitrarily. {@code ext.oracle}
 * reaches its package navigation the same way.
 * <p>
 * The query panel needs no arranging: it activates itself for any Weaviate result set.
 */
public class WeaviateOpenCollectionDataHandler extends AbstractHandler {

    private static final Log log = Log.getLog(WeaviateOpenCollectionDataHandler.class);

    /**
     * The Data tab of the entity editor, from {@code ui.editors.data}'s own registration. Passed
     * explicitly rather than left to the navigator's default-page preference, which is empty out of
     * the box -- the whole point of this action is the data, so it should not depend on a setting.
     */
    private static final String DATA_PAGE_ID = "org.jkiss.dbeaver.ui.editors.data.DatabaseDataEditor";

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateShardGroup group = shardGroupOf(selection);
        if (group == null) {
            return null;
        }
        WeaviateDataSource dataSource = dataSourceOf(selection);
        if (dataSource == null) {
            return null;
        }
        String name = group.getCollectionName();

        // The collection list is usually already in memory -- the shard groups were named from it
        // -- but the Collections folder need not have been expanded, and building the target node
        // can itself need a monitor. Both happen here rather than on the UI thread.
        DBNDatabaseNode[] target = new DBNDatabaseNode[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    for (WeaviateCollection collection : dataSource.getCollections(monitor)) {
                        if (collection.getName().equals(name)) {
                            target[0] = DBNUtils.getNodeByObject(monitor, collection, true);
                            return;
                        }
                    }
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot open the data of " + name, e.getTargetException());
            DBWorkbench.getPlatformUI().showError("Weaviate",
                e.getTargetException().getMessage(), e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }

        if (target[0] == null) {
            // The shard survived its collection, or the tree is stale. Say which, rather than
            // doing nothing and leaving the double-click looking broken.
            DBWorkbench.getPlatformUI().showMessageBox("Weaviate",
                "There is no collection called \"" + name + "\" on this connection any more."
                    + " Refresh the connection to update the shard list.", true);
            return null;
        }
        NavigatorHandlerObjectOpen.openEntityEditor(
            target[0], DATA_PAGE_ID, HandlerUtil.getActiveWorkbenchWindow(event));
        return null;
    }

    @Nullable
    private static WeaviateShardGroup shardGroupOf(@Nullable ISelection selection) {
        if (selection == null) {
            return null;
        }
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            if (node instanceof DBNDatabaseNode databaseNode
                && databaseNode.getObject() instanceof WeaviateShardGroup group
            ) {
                return group;
            }
        }
        return null;
    }

    @Nullable
    private static WeaviateDataSource dataSourceOf(@Nullable ISelection selection) {
        if (selection == null) {
            return null;
        }
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            if (node instanceof DBNDatabaseNode databaseNode) {
                DBSObject object = databaseNode.getObject();
                if (object != null && object.getDataSource() instanceof WeaviateDataSource ds) {
                    return ds;
                }
            }
        }
        return null;
    }
}
