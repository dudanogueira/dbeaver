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

    private static final org.jkiss.dbeaver.Log LOG =
        org.jkiss.dbeaver.Log.getLog(WeaviateShardGroup.class);

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

    /**
     * The collection, with how its shards are doing: {@code MyCollection (4 READY, 1 READONLY)}.
     * <p>
     * The breakdown is in the label because that is the question this node exists to answer. A
     * node hosting a multi-tenant collection has a shard per tenant, so the list underneath can
     * run to thousands, and "are they all healthy" is not a thing anyone should have to scroll to
     * find out. It is also where the individual shards already put their own status, so the two
     * levels read the same way.
     * <p>
     * Ordered by count, largest first, so the dominant state leads and the exceptions sit at the
     * end where they stand out.
     */
    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        String summary = getStatusSummary();
        return summary.isEmpty() ? collection : collection + " (" + summary + ")";
    }

    /** The collection this group is for, undecorated. */
    @NotNull
    public String getCollectionName() {
        return collection;
    }

    /**
     * How many shards are in each state, as {@code 4 READY, 1 READONLY}. Empty when no shard
     * reports one, in which case the label falls back to a plain shard count.
     */
    @NotNull
    @Property(viewable = true, order = 2)
    public String getStatusSummary() {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        int unknown = 0;
        for (WeaviateShard shard : shards) {
            String status = shard.getVectorIndexingStatus();
            if (status == null) {
                unknown++;
            } else {
                counts.merge(status, 1, Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            // Nothing known yet -- this collection has not been expanded, so the breakdown has
            // not been paid for. A shard count is honest and free.
            return unknown == 0 ? "" : unknown + (unknown == 1 ? " shard" : " shards");
        }
        List<java.util.Map.Entry<String, Integer>> ordered = new java.util.ArrayList<>(counts.entrySet());
        // Largest first, then alphabetically, so the same set of shards always reads the same way.
        ordered.sort(java.util.Comparator
            .comparing(java.util.Map.Entry<String, Integer>::getValue).reversed()
            .thenComparing(java.util.Map.Entry::getKey));

        StringBuilder summary = new StringBuilder();
        for (java.util.Map.Entry<String, Integer> entry : ordered) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(entry.getValue()).append(' ').append(entry.getKey());
        }
        if (unknown > 0) {
            summary.append(", ").append(unknown).append(" unknown");
        }
        return summary.toString();
    }

    @Property(viewable = true, order = 3)
    public int getShardCount() {
        return shards.size();
    }

    /**
     * Total objects across this collection's shards on this node.
     */
    @Property(viewable = true, order = 4)
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

    /**
     * Shards not already in {@code target}, which is what an action would change.
     * <p>
     * A shard reporting LAZY_LOADING or INDEXING counts as needing the change: those are not
     * {@code target}, and asking for READY is a legitimate request whatever the shard is currently
     * doing. Only a shard whose status is unreadable is left out, since there is nothing to
     * compare.
     */
    @NotNull
    public List<WeaviateShard> getShardsToChange(@NotNull WeaviateShardStatus target) {
        List<WeaviateShard> changing = new java.util.ArrayList<>();
        for (WeaviateShard shard : shards) {
            String status = shard.getVectorIndexingStatus();
            if (status != null && !status.equalsIgnoreCase(target.name())) {
                changing.add(shard);
            }
        }
        return changing;
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
