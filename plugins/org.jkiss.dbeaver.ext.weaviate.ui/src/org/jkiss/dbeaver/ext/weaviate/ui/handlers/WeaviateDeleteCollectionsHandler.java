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
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectDelete;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Delete All Collections", on the Collections folder.
 * <p>
 * Gathers the collection nodes and hands them to
 * {@link NavigatorHandlerObjectDelete#tryDeleteObjects}, the same entry point the platform's own
 * Delete uses. So this is a shortcut for selecting everything in the folder and nothing more: the
 * confirmation, the object list, the "Show script" preview and the tree update are all the
 * platform's, and behave exactly as they do for a selection made by hand.
 * <p>
 * The first version of this class did the deleting itself, and every part of that was worse. Its
 * confirmation had no script preview; it reported failures in a message box the platform would
 * have presented properly; and it put the tree right by hand, which it did by refreshing the
 * connection -- which at the time meant reconnecting, shutting the HTTP pool down underneath
 * deletes that were still running. Delegating removes all of that code, and the bugs with it.
 * <p>
 * Nothing here handles a plain multi-selection: selecting several collections and pressing Delete
 * already arrives at the same place.
 */
public class WeaviateDeleteCollectionsHandler extends AbstractHandler {

    private static final Log log = Log.getLog(WeaviateDeleteCollectionsHandler.class);

    /** The Collections folder has no {@code id=}, so its node id comes from its items' path. */
    private static final String COLLECTIONS_FOLDER = "collection";

    /** The Collections folder of a Weaviate connection, or null for anything else. */
    @Nullable
    private static DBNDatabaseFolder collectionsFolder(@Nullable ISelection selection) {
        if (selection == null) {
            return null;
        }
        List<DBNNode> nodes = NavigatorUtils.getSelectedNodes(selection);
        if (nodes.size() != 1 || !(nodes.get(0) instanceof DBNDatabaseFolder folder)) {
            return null;
        }
        if (!COLLECTIONS_FOLDER.equals(folder.getNodeId())
            || !(folder.getParentObject() instanceof DBSObject parent)
            || !(parent.getDataSource() instanceof WeaviateDataSource)
        ) {
            return null;
        }
        return folder;
    }

    @Nullable
    private static ISelection selectionFrom(Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? sel : null;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(collectionsFolder(selectionFrom(evaluationContext)) != null);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        DBNDatabaseFolder folder = collectionsFolder(HandlerUtil.getCurrentSelection(event));
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        if (folder == null || window == null) {
            return null;
        }
        List<DBNNode> collections = collectionNodes(folder);
        if (collections == null) {
            return null;
        }
        if (collections.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(
                "Delete collections", "There are no collections to delete.", false);
            return null;
        }
        NavigatorHandlerObjectDelete.tryDeleteObjects(window, collections);
        return null;
    }

    /**
     * The folder's collection nodes, read under progress: the folder may not have been expanded
     * yet, and loading it reaches the server.
     *
     * @return the nodes, or null if the read failed or was cancelled
     */
    @Nullable
    private static List<DBNNode> collectionNodes(@NotNull DBNDatabaseFolder folder) {
        List<DBNNode>[] holder = new List[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    List<DBNNode> found = new ArrayList<>();
                    DBNNode[] children = folder.getChildren(monitor);
                    if (children != null) {
                        for (DBNNode child : children) {
                            if (child instanceof DBNDatabaseNode databaseNode
                                && databaseNode.getObject() instanceof WeaviateCollection
                            ) {
                                found.add(child);
                            }
                        }
                    }
                    holder[0] = found;
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot read the collection list", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                "Delete collections", "Cannot read the collection list", e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }
        return holder[0];
    }
}
