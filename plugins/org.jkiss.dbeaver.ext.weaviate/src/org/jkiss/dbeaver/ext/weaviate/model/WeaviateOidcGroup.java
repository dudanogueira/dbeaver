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
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.ArrayList;
import java.util.List;

/**
 * An OIDC group the server has seen, as a navigator node.
 * <p>
 * Weaviate does not own these. They come from the identity provider, and all Weaviate stores is
 * which roles a group id has been granted -- so there is nothing to create or delete here, only
 * assignments to add and remove.
 */
public class WeaviateOidcGroup implements DBSObject, DBPImageProvider, DBPToolTipObject {

    private final WeaviateDataSource dataSource;
    private final String groupId;
    private volatile List<String> roles;

    public WeaviateOidcGroup(@NotNull WeaviateDataSource dataSource, @NotNull String groupId) {
        this.dataSource = dataSource;
        this.groupId = groupId;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return groupId;
    }

    /**
     * The roles granted to this group.
     * <p>
     * Fetched per group and cached, unlike a role's permissions: the group listing returns names
     * only, so this is a genuine extra read and worth doing once.
     */
    @NotNull
    public List<String> getRoleNames(@NotNull DBRProgressMonitor monitor) throws DBException {
        List<String> known = roles;
        if (known == null) {
            List<String> loaded = new ArrayList<>();
            for (WeaviateRbacRest.RoleInfo role
                : WeaviateRbacRest.groupRoles(dataSource, groupId)) {
                loaded.add(role.name());
            }
            loaded.sort(String::compareToIgnoreCase);
            roles = loaded;
            known = loaded;
        }
        return known;
    }

    public void resetRoleCache() {
        roles = null;
    }

    @NotNull
    @Association
    public List<WeaviateRoleRef> getRoleRefs(@NotNull DBRProgressMonitor monitor) throws DBException {
        List<WeaviateRoleRef> result = new ArrayList<>();
        for (String role : getRoleNames(monitor)) {
            result.add(new WeaviateRoleRef(this, role));
        }
        return result;
    }

    @Nullable
    @Override
    public String getDescription() {
        List<String> known = roles;
        return known == null || known.isEmpty() ? null : String.join(", ", known);
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        return "Managed by the identity provider; Weaviate stores only its role assignments";
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TREE_USER_GROUP;
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
