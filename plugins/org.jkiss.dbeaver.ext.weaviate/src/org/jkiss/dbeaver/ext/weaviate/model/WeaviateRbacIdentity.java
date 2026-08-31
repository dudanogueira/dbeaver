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
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.meta.Property;

import java.util.List;

/**
 * Who this connection is authenticated as, from {@code /v1/users/own-info}.
 * <p>
 * A row rather than a menu action, because "which of these buttons will the server actually let
 * me press" is a question you have before you press anything. It is also the cheapest answer to
 * "why is this folder empty": if the roles list is null the server has RBAC switched off, and no
 * other endpoint distinguishes that from a server where nobody has defined a role.
 */
public class WeaviateRbacIdentity extends WeaviateSecurityEntry
    implements DBPImageProvider, DBPToolTipObject {

    private final String username;
    private final List<String> roles;
    private final List<String> groups;

    public WeaviateRbacIdentity(
        @NotNull WeaviateDataSource dataSource,
        @Nullable String username,
        @NotNull List<String> roles,
        @NotNull List<String> groups
    ) {
        super(dataSource);
        this.username = username == null || username.isBlank() ? "(unnamed)" : username;
        this.roles = List.copyOf(roles);
        this.groups = List.copyOf(groups);
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return "Signed in as " + username;
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getRoles() {
        return roles.isEmpty() ? "none" : String.join(", ", roles);
    }

    @NotNull
    @Property(viewable = true, order = 3)
    public String getGroups() {
        return groups.isEmpty() ? "none" : String.join(", ", groups);
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 4)
    public String getDescription() {
        StringBuilder sb = new StringBuilder();
        sb.append(roles.isEmpty() ? "no roles" : "roles: " + String.join(", ", roles));
        if (!groups.isEmpty()) {
            sb.append("; groups: ").append(String.join(", ", groups));
        }
        return sb.toString();
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        return getDescription();
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TYPE_OBJECT;
    }
}
