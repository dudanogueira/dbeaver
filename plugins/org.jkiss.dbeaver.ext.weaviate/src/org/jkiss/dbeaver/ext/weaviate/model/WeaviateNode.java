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

import io.weaviate.client6.v1.api.cluster.CollectionStats;
import io.weaviate.client6.v1.api.cluster.Node;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class WeaviateNode implements DBSObject, DBSObjectContainer {

    private final WeaviateDataSource dataSource;
    private final Node node;
    private List<WeaviateShard> shards;

    public WeaviateNode(@NotNull WeaviateDataSource dataSource, @NotNull Node node) {
        this.dataSource = dataSource;
        this.node = node;
    }

    @NotNull
    @Override
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 1)
    public String getName() {
        return node.name();
    }

    @Nullable
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 2)
    public String getStatus() {
        return node.status() == null ? null : node.status().name();
    }

    @Nullable
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 3)
    public String getVersion() {
        return node.version();
    }

    @Nullable
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 4)
    public String getGitHash() {
        return node.gitHash();
    }

    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 5)
    public long getObjectCount() {
        CollectionStats stats = node.stats();
        return stats == null ? 0 : stats.objectCount();
    }

    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 6)
    public int getShardCount() {
        CollectionStats stats = node.stats();
        return stats == null ? 0 : stats.shardCount();
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
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

    @Association
    public List<WeaviateShard> getShards(@NotNull DBRProgressMonitor monitor) {
        if (shards == null) {
            List<WeaviateShard> result = new ArrayList<>();
            if (node.shards() != null) {
                for (io.weaviate.client6.v1.api.cluster.Shard shard : node.shards()) {
                    result.add(new WeaviateShard(this, shard));
                }
            }
            shards = result;
        }
        return shards;
    }

    @NotNull
    @Override
    public Collection<WeaviateShard> getChildren(@NotNull DBRProgressMonitor monitor) {
        return getShards(monitor);
    }

    @Nullable
    @Override
    public WeaviateShard getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName) {
        return DBUtils.findObject(getShards(monitor), childName);
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) {
        return WeaviateShard.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope) throws DBException {
        getShards(monitor);
    }
}
