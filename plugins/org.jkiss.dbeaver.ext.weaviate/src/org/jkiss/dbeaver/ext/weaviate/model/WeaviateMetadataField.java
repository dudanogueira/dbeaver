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
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.struct.DBSObject;

public class WeaviateMetadataField implements DBSObject {

    private final DBSObject parent;
    private final String name;
    private final String value;

    public WeaviateMetadataField(@NotNull DBSObject parent, @NotNull String name, @Nullable String value) {
        this.parent = parent;
        this.name = name;
        this.value = value;
    }

    @NotNull
    @Override
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 1)
    public String getName() {
        return value == null || value.isEmpty() ? name : name + ": " + value;
    }

    @NotNull
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 2)
    public String getFieldName() {
        return name;
    }

    @Nullable
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 3)
    public String getValue() {
        return value;
    }

    @Nullable
    @Override
    public String getDescription() {
        return value;
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return parent;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return parent.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }
}
