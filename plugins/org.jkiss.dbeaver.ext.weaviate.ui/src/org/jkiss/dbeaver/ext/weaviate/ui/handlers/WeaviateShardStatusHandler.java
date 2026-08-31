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
import org.eclipse.core.expressions.IEvaluationContext;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenant;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateNode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateShard;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateShardGroup;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateShardStatus;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateInactiveTenantShardsDialog;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;
import org.jkiss.dbeaver.ui.navigator.dialogs.NavigatorNodesDeletionConfirmations;
import org.jkiss.dbeaver.ui.dialogs.Reply;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sets shard status from any level of the tree: a shard, a collection's shards, a node's Shards
 * folder, a whole node, or any mixture of those across several nodes.
 * <p>
 * One command rather than one per level. The levels differ only in how many shards they stand
 * for, and a selection is free to mix them -- two nodes and a stray collection is a perfectly
 * ordinary thing to select, and it should be one request set, not three separate menu entries.
 * <p>
 * The direction is decided by what is there, not by which level was clicked:
 * <ul>
 *   <li>any READONLY shard under the selection, and the entry offers <b>READY</b> -- the
 *       direction that restores service, and the reason anyone looks at this menu;</li>
 *   <li>otherwise it offers <b>READONLY</b>, which is what you want before maintenance.</li>
 * </ul>
 * Writing is per collection because that is the only endpoint that accepts a change, so a
 * selection spanning collections becomes one request each. Reading costs nothing extra: the
 * status travels in the nodes response the tree is already built from.
 */
public class WeaviateShardStatusHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateShardStatusHandler.class);

    /** The Shards folder under a cluster node, which stands for every shard on that node. */
    private static final String SHARDS_FOLDER = "shardGroups";

    /**
     * Whether this node is one the action can act on. Shape only, no fetching: this runs while
     * the context menu is being built.
     */
    private static boolean isShardBearing(@NotNull DBNNode node) {
        if (node instanceof DBNDatabaseFolder folder) {
            return SHARDS_FOLDER.equals(folder.getNodeId())
                && folder.getParentObject() instanceof WeaviateNode;
        }
        if (!(node instanceof DBNDatabaseNode databaseNode)) {
            return false;
        }
        DBSObject object = databaseNode.getObject();
        return object instanceof WeaviateShard
            || object instanceof WeaviateShardGroup
            || object instanceof WeaviateNode;
    }

    /**
     * Every shard the selection stands for, deduplicated.
     * <p>
     * Selecting a node and one of its collections should not act on those shards twice, and with
     * replication the same shard name appears under more than one node -- which is one shard to
     * the endpoint that changes it. Keyed on collection and name for exactly that reason.
     *
     * @param monitor null to use only what is already loaded, for callers that must not fetch
     */
    @NotNull
    private static List<WeaviateShard> shardsOf(
        @Nullable ISelection selection, @Nullable DBRProgressMonitor monitor
    ) {
        if (selection == null) {
            return List.of();
        }
        Map<String, WeaviateShard> unique = new LinkedHashMap<>();
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            if (!isShardBearing(node)) {
                // A selection containing anything else is not an instruction about shards.
                return List.of();
            }
            for (WeaviateShard shard : shardsUnder(node, monitor)) {
                unique.putIfAbsent(shard.getCollection() + "/" + shard.getShardName(), shard);
            }
        }
        return new ArrayList<>(unique.values());
    }

    @NotNull
    private static List<WeaviateShard> shardsUnder(
        @NotNull DBNNode node, @Nullable DBRProgressMonitor monitor
    ) {
        if (node instanceof DBNDatabaseFolder folder) {
            return folder.getParentObject() instanceof WeaviateNode clusterNode
                ? shardsOfNode(clusterNode, monitor) : List.of();
        }
        if (!(node instanceof DBNDatabaseNode databaseNode)) {
            return List.of();
        }
        DBSObject object = databaseNode.getObject();
        if (object instanceof WeaviateShard shard) {
            return List.of(shard);
        }
        if (object instanceof WeaviateShardGroup group) {
            return group.getShards(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor());
        }
        if (object instanceof WeaviateNode clusterNode) {
            return shardsOfNode(clusterNode, monitor);
        }
        return List.of();
    }

    @NotNull
    private static List<WeaviateShard> shardsOfNode(
        @NotNull WeaviateNode clusterNode, @Nullable DBRProgressMonitor monitor
    ) {
        List<WeaviateShardGroup> groups = monitor == null
            ? clusterNode.getLoadedShardGroups()
            : clusterNode.getShardGroups(monitor);
        if (groups == null) {
            // Not expanded yet and we are not allowed to fetch. The caller shows a plain label;
            // execute() asks again with a monitor.
            return List.of();
        }
        List<WeaviateShard> shards = new ArrayList<>();
        for (WeaviateShardGroup group : groups) {
            shards.addAll(group.getShards(new org.jkiss.dbeaver.model.runtime.VoidProgressMonitor()));
        }
        return shards;
    }

    /**
     * The navigator nodes for every shard the selection stands for.
     * <p>
     * Nodes rather than model objects because the confirmation lists them, and the platform's
     * object table renders a {@code DBNNode} -- name, type and description -- and silently skips
     * anything else. Materialising them also loads a node that has not been expanded, which is
     * why this runs under a progress monitor and the label path does not use it.
     */
    @NotNull
    private static List<DBNNode> shardNodesOf(
        @Nullable ISelection selection, @NotNull DBRProgressMonitor monitor
    ) throws DBException {
        if (selection == null) {
            return List.of();
        }
        Map<String, DBNNode> unique = new LinkedHashMap<>();
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            if (!isShardBearing(node)) {
                return List.of();
            }
            for (DBNNode shardNode : shardNodesUnder(node, monitor)) {
                WeaviateShard shard = shardOf(shardNode);
                if (shard != null) {
                    // Same key as the model path: a node and one of its collections must not
                    // contribute the same shard twice, and a replicated shard appears under
                    // several nodes while being one shard to the endpoint that changes it.
                    unique.putIfAbsent(shard.getCollection() + "/" + shard.getShardName(), shardNode);
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    @NotNull
    private static List<DBNNode> shardNodesUnder(
        @NotNull DBNNode node, @NotNull DBRProgressMonitor monitor
    ) throws DBException {
        if (shardOf(node) != null) {
            return List.of(node);
        }
        List<DBNNode> shards = new ArrayList<>();
        DBNNode[] children = node.getChildren(monitor);
        if (children == null) {
            return shards;
        }
        for (DBNNode child : children) {
            if (shardOf(child) != null) {
                shards.add(child);
            } else if (isContainerOfShards(child)) {
                shards.addAll(shardNodesUnder(child, monitor));
            }
        }
        return shards;
    }

    /** Whether descending into this node could reach shards. */
    private static boolean isContainerOfShards(@NotNull DBNNode node) {
        if (node instanceof DBNDatabaseFolder folder) {
            return SHARDS_FOLDER.equals(folder.getNodeId());
        }
        return node instanceof DBNDatabaseNode databaseNode
            && databaseNode.getObject() instanceof WeaviateShardGroup;
    }

    @Nullable
    private static WeaviateShard shardOf(@NotNull DBNNode node) {
        return node instanceof DBNDatabaseNode databaseNode
            && databaseNode.getObject() instanceof WeaviateShard shard ? shard : null;
    }

    /**
     * READY when anything under the selection is read-only, READONLY when everything is already
     * serving. Null when nothing is known yet, which is not the same as nothing to do.
     */
    @Nullable
    private static WeaviateShardStatus targetFor(@NotNull List<WeaviateShard> shards) {
        boolean known = false;
        for (WeaviateShard shard : shards) {
            String status = shard.getVectorIndexingStatus();
            if (status == null) {
                continue;
            }
            known = true;
            if (WeaviateShardStatus.fromName(status) == WeaviateShardStatus.READONLY) {
                return WeaviateShardStatus.READY;
            }
        }
        return known ? WeaviateShardStatus.READONLY : null;
    }

    /** Shards that are not already in {@code target}. */
    @NotNull
    private static List<WeaviateShard> changing(
        @NotNull List<WeaviateShard> shards, @NotNull WeaviateShardStatus target
    ) {
        List<WeaviateShard> changing = new ArrayList<>();
        for (WeaviateShard shard : shards) {
            String status = shard.getVectorIndexingStatus();
            if (status != null && !status.equalsIgnoreCase(target.name())) {
                changing.add(shard);
            }
        }
        return changing;
    }

    @Nullable
    private static WeaviateDataSource dataSourceOf(@NotNull List<WeaviateShard> shards) {
        return shards.isEmpty() || !(shards.get(0).getDataSource() instanceof WeaviateDataSource ds)
            ? null : ds;
    }

    /**
     * Inactive tenants of the collections about to be changed, which are exactly the shards the
     * tree could not show.
     * <p>
     * Best effort per collection: one that will not report its tenants is left out rather than
     * failing the action, since the visible shards can still be changed.
     */
    @NotNull
    private static List<WeaviateInactiveTenantShardsDialog.Entry> findInactiveTenants(
        @Nullable WeaviateDataSource dataSource, @NotNull Set<String> collectionNames
    ) {
        List<WeaviateInactiveTenantShardsDialog.Entry> hidden = new ArrayList<>();
        if (dataSource == null) {
            return hidden;
        }
        try {
            UIUtils.runInProgressService(monitor -> {
                for (String name : collectionNames) {
                    try {
                        WeaviateCollection collection = dataSource.getChild(monitor, name);
                        if (collection == null || !collection.isMultiTenant()) {
                            continue;
                        }
                        for (WeaviateTenant tenant : collection.listTenants(monitor)) {
                            if (!tenant.isActive()) {
                                hidden.add(new WeaviateInactiveTenantShardsDialog.Entry(
                                    name, tenant.name()));
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Cannot read tenants of " + name, e);
                    }
                }
            });
        } catch (InvocationTargetException | InterruptedException e) {
            log.debug("Cannot look for inactive tenants", e);
        }
        return hidden;
    }

    @Nullable
    private static ISelection selectionFrom(Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? sel : null;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        ISelection selection = selectionFrom(evaluationContext);
        if (selection == null) {
            setBaseEnabled(false);
            return;
        }
        List<DBNNode> nodes = NavigatorUtils.getSelectedNodes(selection);
        if (nodes.isEmpty()) {
            setBaseEnabled(false);
            return;
        }
        // Shape only. Whether there is anything to change needs the shards, and a node that has
        // not been expanded would have to be fetched to find out -- on the UI thread, while the
        // menu is being built. execute() settles it properly.
        for (DBNNode node : nodes) {
            if (!isShardBearing(node)) {
                setBaseEnabled(false);
                return;
            }
        }
        setBaseEnabled(true);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);

        List<DBNNode>[] holder = new List[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    holder[0] = shardNodesOf(selection, monitor);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot read shards", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                "Shard status", "Cannot read the shard list", e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }

        List<DBNNode> shardNodes = holder[0] == null ? List.of() : holder[0];
        List<WeaviateShard> shards = new ArrayList<>(shardNodes.size());
        for (DBNNode node : shardNodes) {
            WeaviateShard shard = shardOf(node);
            if (shard != null) {
                shards.add(shard);
            }
        }

        WeaviateShardStatus target = targetFor(shards);
        if (target == null) {
            DBWorkbench.getPlatformUI().showMessageBox(
                "Shard status", "No shard status could be read for this selection.", false);
            return null;
        }

        // Only the ones that would actually change are listed, so the table is what is about to
        // happen rather than what was selected.
        List<DBNNode> changingNodes = new ArrayList<>();
        List<WeaviateShard> toChange = new ArrayList<>();
        for (DBNNode node : shardNodes) {
            WeaviateShard shard = shardOf(node);
            String status = shard == null ? null : shard.getVectorIndexingStatus();
            if (status != null && !status.equalsIgnoreCase(target.name())) {
                changingNodes.add(node);
                toChange.add(shard);
            }
        }
        if (toChange.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox("Shard status",
                "Every shard here is already " + target.name() + ".", false);
            return null;
        }

        // One request per collection: the endpoint that accepts a change takes a collection and a
        // list of its shard names, so a selection spanning collections is that many requests.
        Map<String, List<String>> byCollection = new LinkedHashMap<>();
        for (WeaviateShard shard : toChange) {
            byCollection.computeIfAbsent(shard.getCollection(), c -> new ArrayList<>())
                .add(shard.getShardName());
        }

        // A multi-tenant collection hides the shards of its inactive tenants: the cluster API only
        // lists a tenant's shard while it is active, so "every shard of this collection" quietly
        // means "every shard of its active tenants". Offer the rest rather than skip them
        // silently.
        List<WeaviateInactiveTenantShardsDialog.Entry> hidden =
            findInactiveTenants(dataSourceOf(toChange), byCollection.keySet());
        if (!hidden.isEmpty()) {
            WeaviateInactiveTenantShardsDialog dialog = new WeaviateInactiveTenantShardsDialog(
                HandlerUtil.getActiveShell(event), hidden, target.name());
            if (dialog.open() != org.eclipse.jface.dialogs.IDialogConstants.OK_ID) {
                return null;
            }
            for (WeaviateInactiveTenantShardsDialog.Entry entry : dialog.getChosen()) {
                // On a multi-tenant collection the shard name is the tenant name.
                byCollection.computeIfAbsent(entry.collection(), c -> new ArrayList<>())
                    .add(entry.tenant());
            }
        }
        int totalShards = 0;
        for (List<String> names : byCollection.values()) {
            totalShards += names.size();
        }

        // The platform's own confirmation, the one Delete uses: it lists the objects with their
        // names, types and descriptions instead of asking the user to trust a number. Passing a
        // null deleter drops the parts that only make sense for a delete -- the script preview
        // and its options -- and keeps the object table.
        Reply reply = NavigatorNodesDeletionConfirmations.confirm(
            HandlerUtil.getActiveShell(event),
            "Set shard status",
            MessageFormat.format("Set {0} shard(s) to {1}, across {2} collection(s)?",
                totalShards, target.name(), byCollection.size()),
            changingNodes,
            null);
        if (reply != Reply.YES) {
            return null;
        }

        if (!(toChange.get(0).getDataSource() instanceof WeaviateDataSource dataSource)) {
            return null;
        }
        Map<String, String> failed = new LinkedHashMap<>();
        try {
            UIUtils.runInProgressService(monitor -> {
                monitor.beginTask("Set shard status", byCollection.size());
                try {
                    for (Map.Entry<String, List<String>> entry : byCollection.entrySet()) {
                        if (monitor.isCanceled()) {
                            break;
                        }
                        try {
                            dataSource.setShardStatus(monitor, entry.getKey(), entry.getValue(), target);
                        } catch (DBException e) {
                            // One collection refusing should not abandon the rest; what failed is
                            // named once at the end.
                            log.error("Cannot set shard status on " + entry.getKey(), e);
                            failed.put(entry.getKey(),
                                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                        }
                        monitor.worked(1);
                    }
                } finally {
                    monitor.done();
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot set shard status", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                "Shard status", "Failed to set the shard status", e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            // Cancelled partway; whatever was accepted stands, and is recorded below.
        }

        // Record what the server took, so the labels are right without a re-read. Collections that
        // failed keep their old status, which is more truthful than refreshing would be.
        Set<DBSObject> touched = new LinkedHashSet<>();
        for (WeaviateShard shard : toChange) {
            if (!failed.containsKey(shard.getCollection())) {
                shard.setShardStatus(target);
                touched.add(shard.getParentObject());
            }
        }
        for (DBSObject parent : touched) {
            DBUtils.fireObjectUpdate(parent);
        }

        if (!failed.isEmpty()) {
            StringBuilder message = new StringBuilder("These collections were not changed:\n\n");
            failed.forEach((name, reason) -> message.append(name).append(" - ").append(reason).append('\n'));
            DBWorkbench.getPlatformUI().showMessageBox("Shard status", message.toString(), true);
        }
        return null;
    }

    /**
     * Names the direction and how many shards it would touch, from whatever is already loaded.
     * A selection whose shards have not been read yet gets a plain label rather than a fetch.
     */
    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            return;
        }
        List<WeaviateShard> shards = shardsOf(window.getSelectionService().getSelection(), null);
        WeaviateShardStatus target = targetFor(shards);
        if (target == null) {
            element.setText("Set Selected Shards READY");
            return;
        }
        int count = changing(shards, target).size();
        element.setText(MessageFormat.format(
            "Set Selected Shards {0} ({1} to change)", target.name(), count));
        element.setIcon(DBeaverIcons.getImageDescriptor(
            target == WeaviateShardStatus.READY ? UIIcon.BULLET_GREEN : UIIcon.BULLET_BLACK));
    }
}
