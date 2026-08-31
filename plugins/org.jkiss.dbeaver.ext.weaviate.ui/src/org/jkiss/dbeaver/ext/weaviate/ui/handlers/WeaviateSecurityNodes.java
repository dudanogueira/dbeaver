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
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDbUser;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateOidcGroup;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRole;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRolePermission;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns a navigator selection into the security objects an action works on.
 * <p>
 * Shared for the reason {@code WeaviateTenancyNodes} was: getting this wrong is quiet. A resolver
 * that returns nothing produces a menu entry that is simply absent, with no error to follow, and
 * that has already cost this plugin twice. One copy of the climbing rules is easier to keep right
 * than five.
 * <p>
 * The climbing matters because {@code DBNDatabaseFolder#getObject} returns the folder itself, and
 * the folders here are nested -- Roles and Users and Groups all sit inside Security -- so reaching
 * the connection means walking up rather than asking the node.
 */
final class WeaviateSecurityNodes {

    /** Folder ids from the model bundle's plugin.xml. */
    private static final Set<String> SECURITY_FOLDERS =
        Set.of("security", "roles", "dbUsers", "oidcGroups");

    private WeaviateSecurityNodes() {
    }

    @Nullable
    static ISelection selectionOf(@Nullable Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? sel : null;
    }

    /** Whether a node is one of the Security folders, or something inside one. */
    static boolean isSecurityFolder(@Nullable DBNNode node) {
        return node instanceof DBNDatabaseFolder folder
            && SECURITY_FOLDERS.contains(folder.getNodeId());
    }

    /** The folder id when the node is a security folder, so a handler can tell them apart. */
    @Nullable
    static String folderId(@Nullable DBNNode node) {
        return isSecurityFolder(node) ? ((DBNDatabaseFolder) node).getNodeId() : null;
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

    /** Selected objects of one type, dropping anything that is not one. */
    @NotNull
    private static <T> List<T> selectedOf(@Nullable ISelection selection, @NotNull Class<T> type) {
        List<T> result = new ArrayList<>();
        if (selection == null) {
            return result;
        }
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            if (node instanceof DBNDatabaseNode databaseNode
                && type.isInstance(databaseNode.getObject())
            ) {
                result.add(type.cast(databaseNode.getObject()));
            }
        }
        return result;
    }

    @NotNull
    static List<WeaviateRole> selectedRoles(@Nullable ISelection selection) {
        return selectedOf(selection, WeaviateRole.class);
    }

    @NotNull
    static List<WeaviateDbUser> selectedUsers(@Nullable ISelection selection) {
        return selectedOf(selection, WeaviateDbUser.class);
    }

    @NotNull
    static List<WeaviateOidcGroup> selectedGroups(@Nullable ISelection selection) {
        return selectedOf(selection, WeaviateOidcGroup.class);
    }

    /**
     * The role behind the selection, whether a role row or one of its permission rows is picked.
     * <p>
     * Selecting a permission and asking to edit the role it belongs to is the obvious thing to
     * want, and refusing it would be pedantry.
     */
    @Nullable
    static WeaviateRole singleRole(@Nullable ISelection selection) {
        List<WeaviateRole> roles = selectedRoles(selection);
        if (roles.size() == 1) {
            return roles.get(0);
        }
        if (roles.isEmpty()) {
            List<WeaviateRolePermission> permissions =
                selectedOf(selection, WeaviateRolePermission.class);
            if (permissions.size() == 1
                && permissions.get(0).getParentObject() instanceof WeaviateRole role
            ) {
                return role;
            }
        }
        return null;
    }
}
