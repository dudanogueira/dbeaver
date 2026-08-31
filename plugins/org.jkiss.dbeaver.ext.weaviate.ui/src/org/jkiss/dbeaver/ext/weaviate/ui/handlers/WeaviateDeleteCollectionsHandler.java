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
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.navigator.DBNUtils;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
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
    private record Target(@NotNull WeaviateDataSource dataSource, @NotNull List<String> names, boolean all) {
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

        List<String> names = new ArrayList<>();
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
            names.add(collection.getName());
        }
        // One collection is the platform's job; see the class comment.
        return names.size() > 1 && dataSource != null ? new Target(dataSource, names, false) : null;
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

        List<String> names = target.names();
        if (target.all()) {
            // Listed only to say how many there are. The delete itself is one call that does not
            // depend on this list, so a collection created in between still goes.
            try {
                names = new ArrayList<>();
                for (WeaviateCollection collection : dataSource.getCollections(new VoidProgressMonitor())) {
                    names.add(collection.getName());
                }
            } catch (Exception e) {
                log.debug("Cannot count collections before deleting them all", e);
                names = List.of();
            }
            if (names.isEmpty()) {
                DBWorkbench.getPlatformUI().showMessageBox("Delete collections",
                    "There are no collections to delete.", false);
                return null;
            }
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

        List<String> toDelete = names;
        Map<String, String>[] failures = new Map[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    if (target.all()) {
                        dataSource.deleteAllCollections(monitor);
                        failures[0] = Map.of();
                    } else {
                        failures[0] = dataSource.deleteCollections(monitor, toDelete);
                    }
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot delete collections", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                "Delete collections", "Failed to delete collections", e.getTargetException());
        } catch (InterruptedException e) {
            // Cancelled partway; whatever was deleted stays deleted, and the refresh below shows
            // exactly which.
        }

        refresh(dataSource);

        Map<String, String> failed = failures[0];
        if (failed != null && !failed.isEmpty()) {
            StringBuilder message = new StringBuilder("These collections were not deleted:\n\n");
            failed.forEach((name, reason) -> message.append(name).append(" - ").append(reason).append('\n'));
            DBWorkbench.getPlatformUI().showMessageBox(
                "Delete collections", message.toString(), true);
        }
        return null;
    }

    private static void refresh(@NotNull WeaviateDataSource dataSource) {
        try {
            DBNNode node = DBNUtils.getNodeByObject(dataSource);
            if (node != null) {
                node.refreshNode(new VoidProgressMonitor(), WeaviateDeleteCollectionsHandler.class);
            }
        } catch (Exception e) {
            log.debug("Cannot refresh the navigator after deleting collections", e);
        }
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
            : MessageFormat.format("Delete {0} Collections", target.names().size()));
    }
}
