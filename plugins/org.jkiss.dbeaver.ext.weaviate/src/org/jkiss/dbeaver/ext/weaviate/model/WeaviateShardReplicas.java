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
import org.jkiss.dbeaver.model.DBPUniqueObject;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.meta.Property;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.List;
import java.util.Set;

/**
 * One shard and the nodes holding a replica of it.
 * <p>
 * This is the view a move is planned from: where the copies are now, and therefore which nodes
 * are still legal targets. The server refuses a move to a node already in this list, so the
 * dialog subtracts it from what it offers.
 */
public class WeaviateShardReplicas implements DBSObject, DBPImageProvider, DBPToolTipObject, DBPUniqueObject {

    private final WeaviateCollection collection;
    private final WeaviateReplicationRest.ShardReplicas shard;
    /** Nodes that were not holding this shard the last time it was read. */
    private final Set<String> arrived;

    public WeaviateShardReplicas(
        @NotNull WeaviateCollection collection,
        @NotNull WeaviateReplicationRest.ShardReplicas shard,
        @NotNull Set<String> arrived
    ) {
        this.collection = collection;
        this.shard = shard;
        this.arrived = arrived;
    }

    /**
     * The shard and where its copies are: {@code abc123 \u2192 weaviate-0, weaviate-1}.
     * <p>
     * Capped at three node names. A shard replicated across a large cluster would otherwise put
     * every node in the label and push the shard name -- the part that identifies the row -- off
     * the visible width. The full list is in the Nodes column and the tooltip.
     */
    /**
     * A name that stays put while the label changes.
     * <p>
     * The navigator reuses a tree node only when the object's class and <em>unique</em>
     * name both match ({@code DBNDatabaseNode#equalObjects}), and without this interface the
     * unique name is {@code getName()}. This label carries the nodes holding the shard, which is exactly what a movement changes, so
     * every change made the platform treat the row as a different object: the old node was
     * dropped, a new one took its place, and whatever was expanded underneath collapsed.
     * <p>
     * Identity and label are different things. This is the identity.
     */
    @NotNull
    @Override
    public String getUniqueName() {
        return shard.shard();
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        List<String> replicas = shard.replicas();
        if (replicas.isEmpty()) {
            return shard.shard();
        }
        String nodes = replicas.size() <= 3
            ? String.join(", ", replicas)
            : String.join(", ", replicas.subList(0, 3)) + " and " + (replicas.size() - 3) + " more";
        // A movement changes one name in that list, which is easy to miss in the refresh that
        // also redraws everything else. Say which one, until the next read makes it old news.
        return arrived.isEmpty()
            ? shard.shard() + " \u2192 " + nodes
            : shard.shard() + " \u2192 " + nodes + "   (new: " + String.join(", ", arrived) + ")";
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

    /** Nodes this shard has arrived on since the previous read. */
    @NotNull
    public Set<String> getArrived() {
        return arrived;
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        if (shard.replicas().isEmpty()) {
            return "No node reports a replica of this shard";
        }
        String held = "Held by " + String.join(", ", shard.replicas());
        return arrived.isEmpty() ? held
            : held + "\nArrived on " + String.join(", ", arrived) + " since the last look";
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
