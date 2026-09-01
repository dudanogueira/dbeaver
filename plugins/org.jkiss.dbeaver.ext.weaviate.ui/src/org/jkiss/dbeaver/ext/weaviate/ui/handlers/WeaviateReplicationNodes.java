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
package org.jkiss.dbeaver.ext.weaviate.ui.handlers;

import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.ISources;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateReplicationOp;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateShard;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateShardReplicas;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a navigator selection into the replication objects an action works on.
 * <p>
 * Shared for the reason {@code WeaviateSecurityNodes} was: a resolver that returns nothing shows
 * up as a menu entry that is simply absent, with no error to follow.
 */
final class WeaviateReplicationNodes {

    /** Folder ids from the model bundle's plugin.xml. */
    private static final String OPS_FOLDER = "replicationOps";
    private static final String REPLICAS_FOLDER = "shardReplicas";

    private WeaviateReplicationNodes() {
    }

    @Nullable
    static ISelection selectionOf(@Nullable Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? sel : null;
    }

    /** Whether the selection is exactly the Replication operations folder. */
    static boolean isOpsFolder(@Nullable ISelection selection) {
        return isFolder(selection, OPS_FOLDER);
    }

    /** Whether the selection is exactly a collection's Shard Replicas folder. */
    static boolean isReplicasFolder(@Nullable ISelection selection) {
        return isFolder(selection, REPLICAS_FOLDER);
    }

    private static boolean isFolder(@Nullable ISelection selection, @NotNull String id) {
        if (selection == null) {
            return false;
        }
        List<DBNNode> nodes = NavigatorUtils.getSelectedNodes(selection);
        return nodes.size() == 1
            && nodes.get(0) instanceof DBNDatabaseFolder folder
            && id.equals(folder.getNodeId());
    }

    /** Walks up rather than reading the node, because a folder's object is the folder. */
    @Nullable
    static WeaviateDataSource dataSourceOf(@Nullable ISelection selection) {
        if (selection == null) {
            return null;
        }
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            for (DBNNode current = node; current != null; current = current.getParentNode()) {
                if (current instanceof DBNDatabaseNode databaseNode) {
                    DBSObject object = databaseNode.getObject();
                    if (object != null && object.getDataSource() instanceof WeaviateDataSource ds) {
                        return ds;
                    }
                }
            }
        }
        return null;
    }

    @NotNull
    private static <T> List<T> selectedOf(@Nullable ISelection selection, @NotNull Class<T> type) {
        List<T> result = new ArrayList<>();
        if (selection == null) {
            return result;
        }
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            if (node instanceof DBNDatabaseNode databaseNode
                && type.isInstance(databaseNode.getObject())
            ) {
                result.add(type.cast(databaseNode.getObject()));
            }
        }
        return result;
    }

    @NotNull
    static List<WeaviateReplicationOp> selectedOps(@Nullable ISelection selection) {
        return selectedOf(selection, WeaviateReplicationOp.class);
    }

    @NotNull
    static List<WeaviateShard> selectedShards(@Nullable ISelection selection) {
        return selectedOf(selection, WeaviateShard.class);
    }

    @NotNull
    static List<WeaviateShardReplicas> selectedShardReplicas(@Nullable ISelection selection) {
        return selectedOf(selection, WeaviateShardReplicas.class);
    }

    /**
     * The one shard a movement would act on, whichever branch it was picked from.
     * <p>
     * Two entry points describe the same thing differently: a row under Cluster Nodes knows the
     * node it sits on, while a Shard Replicas row knows every node holding it. Both reduce to a
     * collection, a shard name and the set of nodes that already have it.
     */
    /**
     * @param preferredSource the node the action was invoked from, or null when it was invoked
     *                        somewhere that does not name one. This is the node the user pointed
     *                        at, so it is the one the dialog should start on.
     */
    record ShardTarget(@NotNull String collection, @NotNull String shard,
                       @NotNull List<String> holders, @Nullable String preferredSource) {
    }

    @Nullable
    static ShardTarget singleShard(@Nullable ISelection selection) {
        List<WeaviateShardReplicas> replicas = selectedShardReplicas(selection);
        if (replicas.size() == 1) {
            WeaviateShardReplicas row = replicas.get(0);
            // A Shard Replicas row names every holder and singles out none, so there is no
            // node the user can be said to have pointed at.
            return new ShardTarget(row.getCollection().getName(), row.getShardName(),
                row.getReplicas(), null);
        }
        List<WeaviateShard> shards = selectedShards(selection);
        if (shards.size() == 1) {
            WeaviateShard shard = shards.get(0);
            String collection = shard.getCollection();
            if (collection == null) {
                return null;
            }
            // This row sits under one node, and that is the node the user pointed at -- so it
            // is carried through as the source to start on. The full holder list still comes from
            // the sharding state when the action runs, which is the only place it can be trusted.
            String node = shard.getParentObject() == null ? null : shard.getParentObject().getName();
            return new ShardTarget(collection, shard.getShardName(), List.of(), node);
        }
        return null;
    }
}
