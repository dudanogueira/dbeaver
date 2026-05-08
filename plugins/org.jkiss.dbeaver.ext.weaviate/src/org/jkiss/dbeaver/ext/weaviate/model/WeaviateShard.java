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

import io.weaviate.client6.v1.api.cluster.Shard;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.struct.DBSObject;

public class WeaviateShard implements DBSObject {

    private final WeaviateNode parent;
    private final Shard shard;

    public WeaviateShard(@NotNull WeaviateNode parent, @NotNull Shard shard) {
        this.parent = parent;
        this.shard = shard;
    }

    @NotNull
    @Override
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 1)
    public String getName() {
        StringBuilder sb = new StringBuilder(shard.name());
        boolean hasStatus = shard.vectorIndexingStatus() != null;
        if (hasStatus || shard.objectCount() > 0) {
            sb.append(" (");
            if (hasStatus) sb.append(shard.vectorIndexingStatus().name());
            if (hasStatus) sb.append(", ");
            sb.append(shard.objectCount()).append(" objects");
            sb.append(")");
        }
        return sb.toString();
    }

    @Nullable
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 2)
    public String getCollection() {
        return shard.collection();
    }

    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 3)
    public int getObjectCount() {
        return shard.objectCount();
    }

    @Nullable
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 4)
    public String getVectorIndexingStatus() {
        return shard.vectorIndexingStatus() == null ? null : shard.vectorIndexingStatus().name();
    }

    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 5)
    public int getVectorQueueLength() {
        return shard.vectorQueueLenght();
    }

    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 6)
    public boolean isCompressed() {
        return shard.compressed();
    }

    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 7)
    public boolean isLoaded() {
        return shard.loaded();
    }

    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 8)
    public int getNumberOfReplicas() {
        return shard.numberOfReplicas();
    }

    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 9)
    public int getReplicationFactor() {
        return shard.replicationFactor();
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
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
