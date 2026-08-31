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
package org.jkiss.dbeaver.ext.weaviate.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectState;

/**
 * One permission of one role, as a navigator node.
 * <p>
 * The label carries the scope as well as the action, because the action alone is ambiguous in the
 * way that matters: a role with {@code read_data} on one collection and {@code read_data} on
 * another has two rows that are only distinguishable by their scope.
 */
public class WeaviateRolePermission implements DBSObject, DBPImageProvider, DBPToolTipObject, DBPStatefulObject {

    /**
     * A permission this plugin cannot model -- an action or a scope kind from a server newer than
     * this build. It is shown, and marked, and left alone by any edit.
     */
    private static final DBSObjectState STATE_UNRECOGNISED =
        new DBSObjectState("Not editable here", DBIcon.OVER_UNKNOWN);

    private final WeaviateRole role;
    private final WeaviateRbacRest.PermissionInfo permission;

    public WeaviateRolePermission(
        @NotNull WeaviateRole role, @NotNull WeaviateRbacRest.PermissionInfo permission
    ) {
        this.role = role;
        this.permission = permission;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        String scope = permission.describeScope();
        return scope.isEmpty()
            ? WeaviateRbacAction.displayName(permission.action())
            : WeaviateRbacAction.displayName(permission.action()) + "  (" + scope + ")";
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getAction() {
        return permission.action();
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getDomain() {
        return WeaviateRbacAction.domainOf(permission.action());
    }

    @Nullable
    @Property(viewable = true, order = 4)
    public String getScope() {
        String scope = permission.describeScope();
        return scope.isEmpty() ? null : scope;
    }

    @NotNull
    public WeaviateRbacRest.PermissionInfo getPermissionInfo() {
        return permission;
    }

    /** Whether this build knows both the action and its scope kind well enough to edit it. */
    public boolean isRecognised() {
        return WeaviateRbacAction.isKnown(permission.action())
            && (permission.kind() == null || WeaviateRbacKind.isKnown(permission.kind()));
    }

    @Nullable
    @Override
    public String getDescription() {
        if (!isRecognised()) {
            return "This build does not model this permission; it is shown as the server sent it";
        }
        return WeaviateRbacAction.isLegacy(permission.action())
            ? "A permission written by an older Weaviate; the server reads it but will not accept it on a write"
            : null;
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        String description = getDescription();
        return description == null ? getName() : description;
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TREE_ATTRIBUTE;
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return isRecognised() ? DBSObjectState.NORMAL : STATE_UNRECOGNISED;
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
        // Derived from the permission this node was built from.
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return role;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return role.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }
}
