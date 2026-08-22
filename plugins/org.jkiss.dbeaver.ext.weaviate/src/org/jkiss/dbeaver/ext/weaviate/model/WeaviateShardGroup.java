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
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.List;

/**
 * The shards of one collection on one cluster node.
 * <p>
 * A node hosting a multi-tenant collection has one shard per tenant, so a busy node can list
 * hundreds of shards whose names are tenant ids. Flat, there is nothing to say which collection
 * any of them belongs to; this groups them so the collection is the thing you navigate by.
 */
public class WeaviateShardGroup implements DBSObject {

    private final WeaviateNode parent;
    private final String collection;
    private final List<WeaviateShard> shards;

    public WeaviateShardGroup(
        @NotNull WeaviateNode parent,
        @NotNull String collection,
        @NotNull List<WeaviateShard> shards
    ) {
        this.parent = parent;
        this.collection = collection;
        this.shards = shards;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return collection;
    }

    @Property(viewable = true, order = 2)
    public int getShardCount() {
        return shards.size();
    }

    /**
     * Total objects across this collection's shards on this node.
     */
    @Property(viewable = true, order = 3)
    public long getObjectCount() {
        long total = 0;
        for (WeaviateShard shard : shards) {
            total += shard.getObjectCount();
        }
        return total;
    }

    @NotNull
    public List<WeaviateShard> getShards(@NotNull DBRProgressMonitor monitor) {
        return shards;
    }

    @Nullable
    @Override
    public String getDescription() {
        return shards.size() + " shard(s) of " + collection + " on " + parent.getName();
    }

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
