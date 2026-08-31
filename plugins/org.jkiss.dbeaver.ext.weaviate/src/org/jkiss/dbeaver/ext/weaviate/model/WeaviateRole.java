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
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One role, as a navigator node.
 */
public class WeaviateRole implements DBSObject, DBPImageProvider, DBPToolTipObject, DBPStatefulObject {

    /**
     * Roles the server ships and refuses to change.
     * <p>
     * {@code viewer} and {@code admin} are assignable through the API; {@code root} and
     * {@code read-only} come from environment variables. All four are refused on create, update
     * and delete with a 400, so the editor opens them locked rather than letting someone compose
     * an edit the server will throw away. {@code root} is only visible at all to a root user.
     */
    private static final Set<String> BUILT_IN = Set.of("admin", "root", "viewer", "read-only");

    /**
     * A built-in role is not broken, it is fixed -- so a lock, the same overlay an inactive tenant
     * uses for "you cannot act on this right now".
     */
    private static final DBSObjectState STATE_BUILT_IN =
        new DBSObjectState("Built-in", DBIcon.OVER_LOCK);

    private final WeaviateDataSource dataSource;
    private final WeaviateRbacRest.RoleInfo role;

    public WeaviateRole(
        @NotNull WeaviateDataSource dataSource, @NotNull WeaviateRbacRest.RoleInfo role
    ) {
        this.dataSource = dataSource;
        this.role = role;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return role.name();
    }

    @Property(viewable = true, order = 2)
    public int getPermissionCount() {
        return role.permissions().size();
    }

    @Property(viewable = true, order = 3)
    public boolean isBuiltIn() {
        return BUILT_IN.contains(role.name().toLowerCase(Locale.ROOT));
    }

    /** The underlying record, for the editor and for saving. */
    @NotNull
    public WeaviateRbacRest.RoleInfo getRoleInfo() {
        return role;
    }

    @NotNull
    public List<WeaviateRbacRest.PermissionInfo> getPermissionInfos() {
        return role.permissions();
    }

    /**
     * The permissions, as rows.
     * <p>
     * Read from the role that was already fetched rather than re-read per expansion: listing roles
     * returns them whole, so there is nothing to gain from asking again and a tree that refetched
     * on every twist would be slower for no new information.
     */
    @NotNull
    @Association
    public List<WeaviateRolePermission> getPermissions(@NotNull DBRProgressMonitor monitor) {
        List<WeaviateRolePermission> result = new ArrayList<>(role.permissions().size());
        for (WeaviateRbacRest.PermissionInfo permission : role.permissions()) {
            result.add(new WeaviateRolePermission(this, permission));
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 4)
    public String getDescription() {
        int count = role.permissions().size();
        String summary = count + (count == 1 ? " permission" : " permissions");
        return isBuiltIn() ? summary + ", built-in" : summary;
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        return isBuiltIn()
            ? getName() + " is a built-in role and cannot be changed"
            : getDescription();
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TREE_PERMISSIONS;
    }

    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return isBuiltIn() ? STATE_BUILT_IN : DBSObjectState.NORMAL;
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
        // Whether a role is built-in is a property of its name, which cannot change.
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return dataSource;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public boolean isPersisted() {
        return true;
    }
}
