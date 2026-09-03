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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;
import org.jkiss.dbeaver.ui.navigator.actions.NavigatorHandlerObjectOpen;

import java.util.List;
import java.util.Set;

/**
 * Opens the Configuration tab, from wherever the settings it holds are on display.
 * <p>
 * A tab on an editor is only found by someone who already knows it is there. So the same tab has a
 * way in from the tree: on a collection, and on each folder whose contents it can actually change.
 * <p>
 * <b>Only those folders.</b> Offering it on Sharding or on Properties would be offering to edit
 * things the server refuses, which is the failure this whole branch is built to avoid -- the
 * settings that cannot move stay visible and read-only in the tree, and nothing points at an
 * editor for them. Multi-Tenancy is left out for a different reason: its two changeable settings
 * have their own entries a click away, and a second route to them by a different name would read
 * as a third setting.
 */
public class WeaviateEditConfigHandler extends AbstractHandler {

    /**
     * The tabbed-folder id of the configuration editor, which for a {@code type="folder"}
     * contribution is the descriptor's own id.
     */
    private static final String CONFIG_FOLDER_ID =
        "org.jkiss.dbeaver.ext.weaviate.ui.config.WeaviateConfigEditor";

    /**
     * Folder meta ids whose settings the tab can change.
     * <p>
     * Kept in step with {@code WeaviateConfigSetting} by hand, which is a small enough surface to
     * be worth the directness: each folder here has at least one setting the server accepted when
     * the mutability probe asked it.
     */
    private static final Set<String> EDITABLE_FOLDERS =
        Set.of("invertedIndex", "replication", "vectorizers");

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(collectionOf(WeaviateAliasNodes.selectionOf(evaluationContext)) != null);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateCollection collection = collectionOf(selection);
        if (collection == null) {
            return null;
        }
        DBNDatabaseNode node = NavigatorHandlerObjectOpen.getNodeByObject(collection);
        if (node == null) {
            return null;
        }
        NavigatorHandlerObjectOpen.openEntityEditor(
            node, null, CONFIG_FOLDER_ID, null, HandlerUtil.getActiveWorkbenchWindow(event), true);
        return null;
    }

    /**
     * The collection whose configuration this selection is about, or null.
     * <p>
     * A collection row answers for itself. Anything else has to sit under one of the editable
     * folders, which is checked on the way up: a node under Properties climbs through that folder
     * and is refused there, rather than reaching the collection and looking eligible.
     */
    @Nullable
    private static WeaviateCollection collectionOf(@Nullable ISelection selection) {
        if (selection == null) {
            return null;
        }
        List<DBNNode> nodes = NavigatorUtils.getSelectedNodes(selection);
        if (nodes.size() != 1) {
            return null;
        }
        boolean viaEditableFolder = false;
        for (DBNNode current = nodes.get(0); current != null; current = current.getParentNode()) {
            if (current instanceof DBNDatabaseFolder folder) {
                if (!viaEditableFolder && !EDITABLE_FOLDERS.contains(folder.getNodeId())) {
                    return null;
                }
                viaEditableFolder = true;
                continue;
            }
            if (!(current instanceof DBNDatabaseNode databaseNode)) {
                return null;
            }
            DBSObject object = databaseNode.getObject();
            if (object instanceof WeaviateCollection collection) {
                return collection;
            }
            if (!viaEditableFolder) {
                // Something under a collection that is not in an editable folder: a property, a
                // tenant, an alias. Not this action's business.
                return null;
            }
        }
        return null;
    }
}
