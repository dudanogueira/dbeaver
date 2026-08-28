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
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantNode;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Works out which collection a navigator node is talking about, for the tenancy actions.
 * <p>
 * Shared rather than repeated per handler because getting it wrong is quiet: the resolution
 * returning null shows up as a menu entry that simply is not there, with no error to follow. That
 * has already happened twice here -- {@code DBNDatabaseFolder.getObject()} answers with the folder
 * rather than what it hangs under, and nesting Tenants inside Multi-Tenancy put a second folder
 * between a tenancy node and its collection.
 */
final class WeaviateTenancyNodes {

    /** Folder meta ids that stand for a collection's tenancy. */
    private static final Set<String> TENANCY_FOLDERS = Set.of("multiTenancy", "tenants");

    private WeaviateTenancyNodes() {
    }

    /**
     * The multi-tenant collection this node belongs to: the collection itself, its Multi-Tenancy
     * or Tenants folder, or one of its tenants. Null for anything else, including a single-tenant
     * collection -- whether a collection has tenants is a permanent fact about it, so an action
     * whose only outcome would be to explain that it does nothing is better absent.
     */
    @Nullable
    static WeaviateCollection multiTenantCollection(@Nullable DBNNode node) {
        WeaviateCollection collection = owningCollection(node);
        return collection != null && collection.isMultiTenant() ? collection : null;
    }

    /**
     * Same as {@link #multiTenantCollection}, but refuses a tenant node.
     * <p>
     * For settings that belong to the collection rather than to one tenant. Offered on a tenant,
     * "Enable Automatic Creation" reads as though it were about that tenant, which is not what it
     * does -- and the tenant already has its own Activate/Deactivate entry to be confused with.
     */
    @Nullable
    static WeaviateCollection collectionScoped(@Nullable DBNNode node) {
        if (node instanceof DBNDatabaseNode databaseNode
            && databaseNode.getObject() instanceof WeaviateTenantNode) {
            return null;
        }
        return multiTenantCollection(node);
    }

    /**
     * Climbs to the collection that owns this node.
     * <p>
     * The climb is not decoration. Tenants sits inside Multi-Tenancy, so a tenancy folder's parent
     * may be another folder, and a folder answers {@code getObject()} with itself -- one hop lands
     * on a folder, not a collection. Walking until a collection appears handles either depth, and
     * any nesting added later.
     */
    @Nullable
    static WeaviateCollection owningCollection(@Nullable DBNNode node) {
        boolean viaFolder = false;
        for (DBNNode current = node; current != null; current = current.getParentNode()) {
            if (current instanceof DBNDatabaseFolder folder) {
                // Only the tenancy folders offer these actions; Properties or Vectorizers must not.
                if (!viaFolder && !TENANCY_FOLDERS.contains(folder.getNodeId())) {
                    return null;
                }
                viaFolder = true;
                continue;
            }
            if (!(current instanceof DBNDatabaseNode databaseNode)) {
                return null;
            }
            DBSObject object = databaseNode.getObject();
            if (object instanceof WeaviateCollection collection) {
                return collection;
            }
            if (object instanceof WeaviateTenantNode tenant) {
                return tenant.getParentObject() instanceof WeaviateCollection owner ? owner : null;
            }
            return null;
        }
        return null;
    }

    /**
     * Every distinct multi-tenant collection the selection points at, for the settings that can
     * be applied across collections.
     * <p>
     * Deduplicated by identity, because selecting a collection and its Multi-Tenancy folder both
     * resolve to the same collection and applying a change to it twice is not what that means.
     * Nodes that resolve to nothing -- a single-tenant collection, a Properties folder, a tenant
     * -- are dropped rather than failing the whole selection: a stray node in a multi-selection
     * should not make the action disappear.
     */
    @NotNull
    static List<WeaviateCollection> selectedCollections(@Nullable ISelection selection) {
        return distinctCollections(selection, WeaviateTenancyNodes::collectionScoped);
    }

    /**
     * Like {@link #selectedCollections}, but a tenant counts as its collection.
     * <p>
     * For the actions that work on a collection reached through any of its tenancy nodes, where
     * the question is "how many collections is this selection about" -- one, so a dialog can open
     * on it, or several, which no single-collection dialog can answer.
     */
    @NotNull
    static List<WeaviateCollection> selectedTenancyCollections(@Nullable ISelection selection) {
        return distinctCollections(selection, WeaviateTenancyNodes::multiTenantCollection);
    }

    @NotNull
    private static List<WeaviateCollection> distinctCollections(
        @Nullable ISelection selection, @NotNull Function<DBNNode, WeaviateCollection> resolver
    ) {
        List<WeaviateCollection> collections = new ArrayList<>();
        if (selection == null) {
            return collections;
        }
        Set<WeaviateCollection> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            WeaviateCollection collection = resolver.apply(node);
            if (collection != null && seen.add(collection)) {
                collections.add(collection);
            }
        }
        return collections;
    }

    /** Every tenant node in the selection, in selection order. */
    @NotNull
    static List<WeaviateTenantNode> selectedTenants(@Nullable ISelection selection) {
        List<WeaviateTenantNode> tenants = new ArrayList<>();
        if (selection == null) {
            return tenants;
        }
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            if (node instanceof DBNDatabaseNode databaseNode
                && databaseNode.getObject() instanceof WeaviateTenantNode tenant) {
                tenants.add(tenant);
            }
        }
        return tenants;
    }

    @Nullable
    static ISelection selectionOf(@Nullable Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? sel : null;
    }

    @Nullable
    static DBNNode selectedNode(@Nullable Object evaluationContext) {
        ISelection selection = selectionOf(evaluationContext);
        return selection == null ? null : NavigatorUtils.getSelectedNode(selection);
    }
}
