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

import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.ISources;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateAlias;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns a navigator selection into the aliases an action works on.
 * <p>
 * Shared for the reason {@code WeaviateSecurityNodes} is: a resolver that returns nothing produces
 * a menu entry that is simply absent, with no error to follow. One copy of the climbing rules is
 * easier to keep right than three.
 * <p>
 * There are two Aliases folders and they behave differently. The connection-wide one knows no
 * target, so creating from it has to ask for one; a collection's own knows exactly which target is
 * meant. {@link #targetCollectionOf} is what tells them apart, and it climbs rather than reads the
 * node, because {@code DBNDatabaseFolder#getObject} returns the folder itself.
 */
final class WeaviateAliasNodes {

    /** Folder ids from the model bundle's plugin.xml. */
    private static final Set<String> ALIAS_FOLDERS = Set.of("aliases", "collectionAliases");

    private WeaviateAliasNodes() {
    }

    @Nullable
    static ISelection selectionOf(@Nullable Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? sel : null;
    }

    /** The folder id when the node is one of the two Aliases folders, else null. */
    @Nullable
    static String folderId(@Nullable DBNNode node) {
        return node instanceof DBNDatabaseFolder folder
            && ALIAS_FOLDERS.contains(folder.getNodeId())
            ? folder.getNodeId()
            : null;
    }

    /** Whether the selection is exactly one Aliases folder. */
    static boolean isSingleAliasFolder(@Nullable ISelection selection) {
        if (selection == null) {
            return false;
        }
        List<DBNNode> nodes = NavigatorUtils.getSelectedNodes(selection);
        return nodes.size() == 1 && folderId(nodes.get(0)) != null;
    }

    /**
     * The collection a new alias should point at, or null when the selection does not say.
     * <p>
     * Non-null only under a collection's own Aliases folder. Creating from there has already
     * answered "which collection", and asking again would be a question with one right answer
     * already on screen.
     */
    @Nullable
    static String targetCollectionOf(@Nullable ISelection selection) {
        if (selection == null) {
            return null;
        }
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            if (!"collectionAliases".equals(folderId(node))) {
                continue;
            }
            for (DBNNode current = node; current != null; current = current.getParentNode()) {
                if (current instanceof DBNDatabaseNode databaseNode
                    && databaseNode.getObject() instanceof WeaviateCollection collection
                ) {
                    return collection.getName();
                }
            }
        }
        return null;
    }

    /**
     * The Weaviate connection behind a node, whatever kind of node it is.
     * <p>
     * Walks up rather than reading the node, because a folder's object is the folder.
     */
    @Nullable
    static WeaviateDataSource dataSourceOf(@Nullable DBNNode node) {
        for (DBNNode current = node; current != null; current = current.getParentNode()) {
            if (current instanceof DBNDatabaseNode databaseNode) {
                DBSObject object = databaseNode.getObject();
                if (object != null && object.getDataSource() instanceof WeaviateDataSource ds) {
                    return ds;
                }
            }
        }
        return null;
    }

    @Nullable
    static WeaviateDataSource dataSourceOf(@Nullable ISelection selection) {
        if (selection == null) {
            return null;
        }
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            WeaviateDataSource ds = dataSourceOf(node);
            if (ds != null) {
                return ds;
            }
        }
        return null;
    }

    @NotNull
    static List<WeaviateAlias> selectedAliases(@Nullable ISelection selection) {
        List<WeaviateAlias> result = new ArrayList<>();
        if (selection == null) {
            return result;
        }
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            if (node instanceof DBNDatabaseNode databaseNode
                && databaseNode.getObject() instanceof WeaviateAlias alias
            ) {
                result.add(alias);
            }
        }
        return result;
    }
}
