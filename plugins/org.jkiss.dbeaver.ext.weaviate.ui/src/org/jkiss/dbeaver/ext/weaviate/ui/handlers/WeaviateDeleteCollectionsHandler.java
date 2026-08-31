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
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Deletes several collections, or all of them, as one operation.
 * <p>
 * The platform's own Delete stages a command per object, so removing eight collections is eight
 * commands, eight entries in the script preview and eight things to go wrong independently. This
 * is one action, one confirmation and one progress bar.
 * <p>
 * Deliberately <em>not</em> offered for a single collection. That case already works through the
 * platform's delete, which also gives the navigator refresh and the standard confirmation for
 * free, and a second Delete entry beside it would only make the menu ambiguous.
 */
public class WeaviateDeleteCollectionsHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateDeleteCollectionsHandler.class);

    /** The Collections folder has no {@code id=}, so its node id comes from its items' path. */
    private static final String COLLECTIONS_FOLDER = "collection";

    /**
     * What the action would remove: either the named collections, or every collection on the
     * connection.
     */
    private record Target(
        @NotNull WeaviateDataSource dataSource,
        @NotNull List<WeaviateCollection> collections,
        boolean all
    ) {
        @NotNull
        List<String> names() {
            List<String> names = new ArrayList<>(collections.size());
            for (WeaviateCollection collection : collections) {
                names.add(collection.getName());
            }
            return names;
        }
    }

    @Nullable
    private static Target target(@Nullable ISelection selection) {
        if (selection == null) {
            return null;
        }
        List<DBNNode> nodes = NavigatorUtils.getSelectedNodes(selection);

        // The folder means everything, and says so without having to list it first.
        if (nodes.size() == 1 && nodes.get(0) instanceof DBNDatabaseFolder folder) {
            if (!COLLECTIONS_FOLDER.equals(folder.getNodeId())
                || !(folder.getParentObject() instanceof DBSObject parent)
                || !(parent.getDataSource() instanceof WeaviateDataSource ds)
            ) {
                return null;
            }
            return new Target(ds, List.of(), true);
        }

        List<WeaviateCollection> collections = new ArrayList<>();
        WeaviateDataSource dataSource = null;
        for (DBNNode node : nodes) {
            if (!(node instanceof DBNDatabaseNode databaseNode)
                || !(databaseNode.getObject() instanceof WeaviateCollection collection)
            ) {
                return null;
            }
            if (dataSource == null) {
                if (!(collection.getDataSource() instanceof WeaviateDataSource ds)) {
                    return null;
                }
                dataSource = ds;
            } else if (collection.getDataSource() != dataSource) {
                // Two connections cannot be one operation.
                return null;
            }
            collections.add(collection);
        }
        // One collection is the platform's job; see the class comment.
        return collections.size() > 1 && dataSource != null
            ? new Target(dataSource, collections, false) : null;
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
        setBaseEnabled(target(selectionFrom(evaluationContext)) != null);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        Target target = target(HandlerUtil.getCurrentSelection(event));
        if (target == null) {
            return null;
        }
        WeaviateDataSource dataSource = target.dataSource();

        List<WeaviateCollection> collections = target.collections();
        if (target.all()) {
            // Read for three reasons: to put a number in the confirmation, to know which nodes to
            // take out of the tree afterwards, and because the delete itself now works through
            // this list rather than a single server-side "delete everything".
            collections = listCollections(dataSource);
            if (collections == null) {
                return null;
            }
            if (collections.isEmpty()) {
                DBWorkbench.getPlatformUI().showMessageBox("Delete collections",
                    "There are no collections to delete.", false);
                return null;
            }
        }
        List<String> names = new ArrayList<>(collections.size());
        for (WeaviateCollection collection : collections) {
            names.add(collection.getName());
        }

        String question = target.all()
            ? MessageFormat.format(
                "Delete all {0} collections from \"{1}\"?\n\nEvery object in them goes with them. "
                    + "This cannot be undone.",
                names.size(), dataSource.getContainer().getName())
            : MessageFormat.format(
                "Delete {0} collections?\n\n{1}\n\nEvery object in them goes with them. "
                    + "This cannot be undone.",
                names.size(), String.join(", ", names));
        if (!UIUtils.confirmAction(HandlerUtil.getActiveShell(event), "Delete collections", question)) {
            return null;
        }

        List<WeaviateCollection> toDelete = collections;
        Map<String, String>[] failures = new Map[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                List<String> targetNames = new ArrayList<>(toDelete.size());
                for (WeaviateCollection collection : toDelete) {
                    targetNames.add(collection.getName());
                }
                // The same call for both. "Delete everything" used to go through the client's
                // deleteAll, which is one request and therefore one thing that can outlive the
                // read timeout -- and when it did, the delete succeeded on the server while the
                // client reported a failure and the tree kept the nodes.
                failures[0] = dataSource.deleteCollections(monitor, targetNames);
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot delete collections", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                "Delete collections", "Failed to delete collections", e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            // Cancelled partway. Whatever went is gone, and the removals below cover exactly those.
        }

        Map<String, String> failed = failures[0] == null ? Map.of() : failures[0];
        // Take the deleted nodes out of the tree one by one rather than refreshing.
        //
        // Refreshing the connection node was the first attempt and it was wrong twice over: it
        // re-reads the whole schema, which froze the workbench when done on the UI thread, and a
        // datasource refresh reconnects -- shutting the HTTP pool down underneath deletes that
        // were still running, which is where "Connection pool shut down" came from. This is what
        // WeaviateCollectionManager already does after deleting a single collection.
        for (WeaviateCollection collection : toDelete) {
            if (!failed.containsKey(collection.getName())) {
                DBUtils.fireObjectRemove(collection);
            }
        }

        if (!failed.isEmpty()) {
            StringBuilder message = new StringBuilder("These collections were not deleted:\n\n");
            failed.forEach((name, reason) -> message.append(name).append(" - ").append(reason).append('\n'));
            DBWorkbench.getPlatformUI().showMessageBox(
                "Delete collections", message.toString(), true);
        }
        return null;
    }

    /**
     * Reads the collections under a progress dialog, or null if the user cancelled.
     * <p>
     * Under progress rather than inline because it can reach the server, and a UI that freezes
     * only when the cache happens to be cold is worse than one that never does.
     */
    @Nullable
    private static List<WeaviateCollection> listCollections(@NotNull WeaviateDataSource dataSource) {
        List<WeaviateCollection>[] holder = new List[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    holder[0] = new ArrayList<>(dataSource.getCollections(monitor));
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot list collections", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                "Delete collections", "Cannot read the collection list", e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }
        return holder[0];
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            return;
        }
        Target target = target(window.getSelectionService().getSelection());
        if (target == null) {
            return;
        }
        element.setText(target.all()
            ? "Delete All Collections"
            : MessageFormat.format("Delete {0} Collections", target.collections().size()));
    }
}
