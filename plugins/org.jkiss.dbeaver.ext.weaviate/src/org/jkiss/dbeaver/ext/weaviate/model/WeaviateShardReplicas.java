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
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.List;

/**
 * One shard and the nodes holding a replica of it.
 * <p>
 * This is the view a move is planned from: where the copies are now, and therefore which nodes
 * are still legal targets. The server refuses a move to a node already in this list, so the
 * dialog subtracts it from what it offers.
 */
public class WeaviateShardReplicas implements DBSObject, DBPImageProvider, DBPToolTipObject {

    private final WeaviateCollection collection;
    private final WeaviateReplicationRest.ShardReplicas shard;

    public WeaviateShardReplicas(
        @NotNull WeaviateCollection collection,
        @NotNull WeaviateReplicationRest.ShardReplicas shard
    ) {
        this.collection = collection;
        this.shard = shard;
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return shard.replicas().isEmpty()
            ? shard.shard()
            : shard.shard() + "  → " + String.join(", ", shard.replicas());
    }

    /** The shard name on its own, which is what the replicate endpoint expects. */
    @NotNull
    public String getShardName() {
        return shard.shard();
    }

    @NotNull
    public List<String> getReplicas() {
        return shard.replicas();
    }

    @NotNull
    @Property(viewable = true, order = 2)
    public String getNodes() {
        return String.join(", ", shard.replicas());
    }

    /**
     * How many copies of this shard exist.
     * <p>
     * A shard's replication factor is its own and can exceed the collection's -- a copy raises it
     * for that shard alone.
     */
    @Property(viewable = true, order = 3)
    public int getReplicaCount() {
        return shard.replicas().size();
    }

    @NotNull
    public WeaviateCollection getCollection() {
        return collection;
    }

    @Nullable
    @Override
    public String getDescription() {
        int count = shard.replicas().size();
        return count + (count == 1 ? " replica" : " replicas");
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        return shard.replicas().isEmpty()
            ? "No node reports a replica of this shard"
            : "Held by " + String.join(", ", shard.replicas());
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TREE_PARTITION;
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
}
