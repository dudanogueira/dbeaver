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
import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObjectState;
import org.jkiss.dbeaver.model.struct.DBSObject;

public class WeaviateShard implements DBSObject, DBPStatefulObject {

    private final WeaviateNode parent;
    private final WeaviateNodesRest.ShardInfo shard;
    /**
     * Set after this plugin changes the status, so the row reflects it without a re-read.
     * <p>
     * Null normally: the status comes from the nodes response this shard was built from. That
     * field is named {@code vectorIndexingStatus}, which reads as though it were only about the
     * vector index, but it reports READONLY when a shard has been set read-only -- verified by
     * setting one and watching the value change. So there is one status, not two, and no extra
     * request is needed to see it.
     */
    private volatile String statusOverride;

    public WeaviateShard(@NotNull WeaviateNode parent, @NotNull WeaviateNodesRest.ShardInfo shard) {
        this.parent = parent;
        this.shard = shard;
    }

    @NotNull
    @Override
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 1)
    public String getName() {
        StringBuilder sb = new StringBuilder(shard.name());
        String status = getVectorIndexingStatus();
        if (status != null || shard.objectCount() > 0) {
            sb.append(" (");
            if (status != null) {
                sb.append(status).append(", ");
            }
            sb.append(shard.objectCount()).append(" objects");
            sb.append(")");
        }
        return sb.toString();
    }

    /** The shard name on its own, which is what the update endpoint expects. */
    @NotNull
    public String getShardName() {
        return shard.name();
    }

    /**
     * The overlay for this shard's status, coloured per state by {@link WeaviateShardStatus}.
     * <p>
     * An overlay rather than row colour, which the navigator does not offer: its label provider
     * answers {@code getForeground} for datasources, locked nodes and unsaved objects only, with
     * no per-object hook. The overlay is the one channel that carries colour per row, and
     * {@code DBNDatabaseNode#getNodeIcon} applies it unconditionally -- unlike the status text
     * beside the name, which needs a navigator preference that is off by default.
     */
    @NotNull
    @Override
    public DBSObjectState getObjectState() {
        return getShardStatus().getObjectState();
    }

    @Override
    public void refreshObjectState(@NotNull DBRProgressMonitor monitor) {
        // The state travels with the shard snapshot this object was built from, and the node
        // rebuilds its shards when that snapshot is re-read.
    }

    @NotNull
    public WeaviateShardStatus getShardStatus() {
        return WeaviateShardStatus.fromName(getVectorIndexingStatus());
    }

    /**
     * Records the state the server has just accepted.
     * <p>
     * Public because the action that changes a shard's state lives in the UI bundle, and the row's
     * label is built from this field: without it the tree keeps showing the old state until
     * something forces a re-read, which is the same "it did not update" this branch has already
     * chased through tenants, backups and collections.
     */
    public void setShardStatus(@NotNull WeaviateShardStatus status) {
        this.statusOverride = status.name();
    }



    @Nullable
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 2)
    public String getCollection() {
        return shard.collection();
    }

    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 3)
    public long getObjectCount() {
        return shard.objectCount();
    }

    @Nullable
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 4)
    public String getVectorIndexingStatus() {
        if (statusOverride != null) {
            return statusOverride;
        }
        return WeaviateNodesRest.statusOf(shard);
    }

    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 5)
    public int getVectorQueueLength() {
        return shard.vectorQueueLength();
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
