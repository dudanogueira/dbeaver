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

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateNode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateReplicationRest;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateReplicateShardDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateReplicationRefresh;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Starts a replica movement from a shard row.
 * <p>
 * The holders are read from the sharding state at the moment the action runs, not from whatever
 * the tree happened to load: the target must be a node that does not already have the shard, and
 * a stale answer there would offer a target the server then refuses.
 */
public class WeaviateReplicateShardHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateReplicateShardHandler.class);

    private static final String TITLE = "Replica movement";

    @Override
    public void setEnabled(Object evaluationContext) {
        // Shape only. Whether a legal target exists needs the sharding state and the node list,
        // which is a network round trip and this runs while the menu is being built.
        ISelection selection = WeaviateReplicationNodes.selectionOf(evaluationContext);
        setBaseEnabled(selection != null
            && WeaviateReplicationNodes.dataSourceOf(selection) != null
            && WeaviateReplicationNodes.singleShard(selection) != null);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = WeaviateReplicationNodes.dataSourceOf(selection);
        WeaviateReplicationNodes.ShardTarget target =
            WeaviateReplicationNodes.singleShard(selection);
        if (dataSource == null || target == null) {
            return null;
        }

        List<String> holders = new ArrayList<>();
        List<String> allNodes = new ArrayList<>();
        boolean[] disabled = {false};
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    for (WeaviateNode node : dataSource.getNodes(monitor)) {
                        allNodes.add(node.getName());
                    }
                    for (WeaviateReplicationRest.ShardReplicas shard
                        : WeaviateReplicationRest.shardingState(
                            dataSource, target.collection()).shards()) {
                        if (shard.shard().equals(target.shard())) {
                            holders.addAll(shard.replicas());
                        }
                    }
                } catch (WeaviateReplicationRest.DisabledException e) {
                    disabled[0] = true;
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError(TITLE,
                "Cannot read where this shard's replicas are", e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }

        if (disabled[0]) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE,
                "Replica movement is switched off on this server. Start every node with "
                    + "REPLICA_MOVEMENT_ENABLED=true to use it.", true);
            return null;
        }
        if (holders.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(TITLE, MessageFormat.format(
                "No node reports a replica of {0}/{1}, so there is nothing to move.",
                target.collection(), target.shard()), true);
            return null;
        }

        List<String> candidates = new ArrayList<>(allNodes);
        candidates.removeAll(holders);

        WeaviateReplicateShardDialog dialog = new WeaviateReplicateShardDialog(
            HandlerUtil.getActiveShell(event), target.collection(), target.shard(),
            holders, candidates, target.preferredSource());
        if (dialog.open() != IDialogConstants.OK_ID || dialog.getTargetNode() == null) {
            return null;
        }

        String type = dialog.getReplicationType().name();
        String source = dialog.getSourceNode();
        String destination = dialog.getTargetNode();
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    // Fire and forget: the POST answers with an id and the work then runs for as
                    // long as it takes. The operation shows up under Replication straight away.
                    WeaviateReplicationRest.start(dataSource, target.collection(), target.shard(),
                        source, destination, type);
                    WeaviateReplicationRefresh.after(monitor, dataSource);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot start a " + type, e.getTargetException());
            DBWorkbench.getPlatformUI().showError(TITLE, MessageFormat.format(
                "Cannot start a {0} of {1}/{2}", type.toLowerCase(java.util.Locale.ROOT),
                target.collection(), target.shard()), e.getTargetException());
        } catch (InterruptedException e) {
            // Cancelled.
        }
        return null;
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.ARROW_RIGHT));
    }
}
