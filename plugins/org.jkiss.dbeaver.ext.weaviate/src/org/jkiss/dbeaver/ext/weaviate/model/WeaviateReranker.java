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

import io.weaviate.client6.v1.api.collections.Reranker;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.util.Collection;
import java.util.List;

public class WeaviateReranker implements DBSObject, DBSObjectContainer {

    private final WeaviateCollection collection;
    private final int ordinal;
    private final Reranker reranker;
    private List<WeaviateMetadataField> fields;

    public WeaviateReranker(@NotNull WeaviateCollection collection, int ordinal, @NotNull Reranker reranker) {
        this.collection = collection;
        this.ordinal = ordinal;
        this.reranker = reranker;
    }

    @NotNull
    @Override
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 1)
    public String getName() {
        Object kind = reranker._kind();
        return kind == null ? "reranker-" + ordinal : kind.toString();
    }

    @Association
    public List<WeaviateMetadataField> getFields(@NotNull DBRProgressMonitor monitor) {
        if (fields == null) {
            fields = WeaviateRecordIntrospect.toFields(this, reranker);
        }
        return fields;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return collection;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return collection.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @NotNull
    @Override
    public Collection<WeaviateMetadataField> getChildren(@NotNull DBRProgressMonitor monitor) {
        return getFields(monitor);
    }

    @Nullable
    @Override
    public WeaviateMetadataField getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName) {
        for (WeaviateMetadataField f : getFields(monitor)) {
            if (childName.equals(f.getName())) return f;
        }
        return null;
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) {
        return WeaviateMetadataField.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope) throws DBException {
        getFields(monitor);
    }
}
