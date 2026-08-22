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
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityAttributeRef;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraintType;
import org.jkiss.dbeaver.model.struct.DBSEntityReferrer;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.List;

/**
 * Primary key over {@link WeaviateUuidAttribute}.
 * <p>
 * Every Weaviate object has exactly one id and it is unique within the collection, so this is a
 * real key rather than a convenience. {@code DBExecUtils#getBestIdentifier} consults entity
 * constraints before pseudo-attributes, and the identifier it produces is what lets the grid
 * delete a selected row.
 */
public class WeaviateUuidConstraint implements DBSEntityReferrer {

    private final WeaviateCollection collection;
    private final WeaviateUuidAttribute attribute;

    public WeaviateUuidConstraint(
        @NotNull WeaviateCollection collection,
        @NotNull WeaviateUuidAttribute attribute
    ) {
        this.collection = collection;
        this.attribute = attribute;
    }

    @NotNull
    @Override
    public String getName() {
        return collection.getName() + "_pk";
    }

    @NotNull
    @Override
    public DBSEntityConstraintType getConstraintType() {
        return DBSEntityConstraintType.PRIMARY_KEY;
    }

    @NotNull
    @Override
    public List<? extends DBSEntityAttributeRef> getAttributeReferences(
        @Nullable DBRProgressMonitor monitor
    ) {
        return List.of((DBSEntityAttributeRef) () -> attribute);
    }

    @NotNull
    @Override
    public DBSEntity getParentObject() {
        return collection;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return collection.getDataSource();
    }

    @Nullable
    @Override
    public String getDescription() {
        return "Weaviate object ID";
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @Nullable
    public DBSObject getParent() {
        return collection;
    }
}
