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
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.struct.DBSObject;

/**
 * A role held by a user or a group, listed under that user or group.
 * <p>
 * Deliberately not a {@link WeaviateRole}: the same role appears under every holder, and a node
 * whose parent is the role list would give each of those copies the same identity in the
 * navigator. This one knows who is holding it.
 */
public class WeaviateRoleRef implements DBSObject, DBPImageProvider {

    private final DBSObject holder;
    private final String roleName;

    public WeaviateRoleRef(@NotNull DBSObject holder, @NotNull String roleName) {
        this.holder = holder;
        this.roleName = roleName;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return roleName;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TREE_PERMISSIONS;
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return holder;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return holder.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }
}
