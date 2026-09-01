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
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.DBPToolTipObject;
import org.jkiss.dbeaver.model.meta.Property;

/**
 * The row that says why nothing can be moved.
 */
public class WeaviateReplicationAdvice extends WeaviateReplicationEntry
    implements DBPImageProvider, DBPToolTipObject {

    private final String headline;
    private final String detail;

    private WeaviateReplicationAdvice(
        @NotNull WeaviateDataSource dataSource, @NotNull String headline, @NotNull String detail
    ) {
        super(dataSource);
        this.headline = headline;
        this.detail = detail;
    }

    /** The server answered 501: the feature is off, which is a setting somebody can change. */
    @NotNull
    public static WeaviateReplicationAdvice disabled(@NotNull WeaviateDataSource dataSource) {
        return new WeaviateReplicationAdvice(dataSource,
            "Replica movement is not enabled on this server",
            "Start every node with REPLICA_MOVEMENT_ENABLED=true, then restart them.");
    }

    /**
     * One node cannot move a replica anywhere.
     * <p>
     * A move needs a target that does not already hold the shard, so a single-node cluster has
     * nowhere legal to send one. Worth saying before somebody goes looking for the action.
     */
    @NotNull
    public static WeaviateReplicationAdvice singleNode(@NotNull WeaviateDataSource dataSource) {
        return new WeaviateReplicationAdvice(dataSource,
            "This cluster has one node, so there is nowhere to move a replica to",
            "A replica can only move to a node that does not already hold the shard.");
    }

    /** The connection cannot sign a REST request, so none of this is reachable. */
    @NotNull
    public static WeaviateReplicationAdvice needsApiKey(@NotNull WeaviateDataSource dataSource) {
        return new WeaviateReplicationAdvice(dataSource,
            "Replication needs a connection that authenticates with an API key",
            "This connection signs in with a username and password, and the resulting token is "
                + "not reachable from here. Reconnect with an API key to manage replica movement.");
    }

    /** The server refused the read, which usually means the caller lacks read_replicate. */
    @NotNull
    public static WeaviateReplicationAdvice refused(
        @NotNull WeaviateDataSource dataSource, @NotNull String serverMessage
    ) {
        return new WeaviateReplicationAdvice(dataSource,
            "This connection may not read replication operations", serverMessage);
    }

    @NotNull
    @Override
    @Property(viewable = true, order = 1)
    public String getName() {
        return headline;
    }

    @Nullable
    @Override
    @Property(viewable = true, order = 2)
    public String getDescription() {
        return detail;
    }

    @Nullable
    @Override
    public String getObjectToolTip() {
        return detail;
    }

    @NotNull
    @Override
    public DBPImage getObjectImage() {
        return DBIcon.TYPE_UNKNOWN;
    }
}
