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
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateShard;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateShardGroup;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateShardStatus;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sets a shard's read/write state, on one shard or on a whole collection's worth.
 * <p>
 * Only READY and READONLY exist here. The other states a shard row shows -- LAZY_LOADING,
 * INDEXING -- belong to the vector index and describe work the server is doing; no request
 * changes them, so nothing here offers to.
 * <p>
 * On a shard the entry names the change it will make, as Activate/Deactivate Tenant does. On a
 * collection it offers the restorative direction only, and says how many shards it would touch:
 * turning a whole collection read-only is a maintenance decision that deserves to be made shard
 * by shard, while putting it back is the thing you want in one click.
 */
public abstract class WeaviateShardStatusHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateShardStatusHandler.class);

    /** Whether this handler works on a whole collection rather than the selected shards. */
    protected abstract boolean isGroup();

    /** Selected shards of one collection, or empty when the selection is not that. */
    @NotNull
    private static List<WeaviateShard> selectedShards(@Nullable ISelection selection) {
        List<WeaviateShard> shards = new ArrayList<>();
        if (selection == null) {
            return shards;
        }
        String collection = null;
        for (DBNNode node : NavigatorUtils.getSelectedNodes(selection)) {
            if (!(node instanceof DBNDatabaseNode databaseNode)
                || !(databaseNode.getObject() instanceof WeaviateShard shard)
            ) {
                return List.of();
            }
            if (shard.getVectorIndexingStatus() == null) {
                // No status to compare against, so there is no change to name.
                return List.of();
            }
            if (collection == null) {
                collection = shard.getCollection();
            } else if (!collection.equals(shard.getCollection())) {
                // One request per collection, so a selection spanning two is not one action.
                return List.of();
            }
            shards.add(shard);
        }
        return shards;
    }

    @Nullable
    private static WeaviateShardGroup selectedGroup(@Nullable ISelection selection) {
        if (selection == null) {
            return null;
        }
        List<DBNNode> nodes = NavigatorUtils.getSelectedNodes(selection);
        if (nodes.size() != 1 || !(nodes.get(0) instanceof DBNDatabaseNode databaseNode)) {
            return null;
        }
        DBSObject object = databaseNode.getObject();
        return object instanceof WeaviateShardGroup group ? group : null;
    }

    @Nullable
    private static ISelection selectionFrom(Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? sel : null;
    }

    /**
     * What the entry would do: on a group always READY, on shards the opposite of what they are.
     * A mixed selection is offered READY, the direction that restores service.
     */
    @Nullable
    private WeaviateShardStatus targetFor(@Nullable ISelection selection) {
        if (isGroup()) {
            WeaviateShardGroup group = selectedGroup(selection);
            return group == null || group.getShardsToChange(WeaviateShardStatus.READY).isEmpty()
                ? null : WeaviateShardStatus.READY;
        }
        List<WeaviateShard> shards = selectedShards(selection);
        if (shards.isEmpty()) {
            return null;
        }
        // Anything that is not READY -- READONLY, but also LAZY_LOADING or INDEXING -- is offered
        // READY, the direction that restores service and the same one the collection-level action
        // takes. Only a set that is already entirely READY is offered the other way.
        for (WeaviateShard shard : shards) {
            String status = shard.getVectorIndexingStatus();
            if (status != null && !status.equalsIgnoreCase(WeaviateShardStatus.READY.name())) {
                return WeaviateShardStatus.READY;
            }
        }
        return WeaviateShardStatus.READONLY;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(targetFor(selectionFrom(evaluationContext)) != null);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateShardStatus target = targetFor(selection);
        if (target == null) {
            return null;
        }

        List<WeaviateShard> shards;
        String collection;
        DBSObject refreshSubject;
        if (isGroup()) {
            WeaviateShardGroup group = selectedGroup(selection);
            if (group == null) {
                return null;
            }
            shards = group.getShardsToChange(target);
            collection = group.getCollectionName();
            refreshSubject = group;
        } else {
            shards = new ArrayList<>();
            for (WeaviateShard shard : selectedShards(selection)) {
                String status = shard.getVectorIndexingStatus();
                if (status != null && !status.equalsIgnoreCase(target.name())) {
                    shards.add(shard);
                }
            }
            collection = shards.isEmpty() ? null : shards.get(0).getCollection();
            refreshSubject = shards.isEmpty() ? null : shards.get(0);
        }
        if (shards.isEmpty() || collection == null) {
            return null;
        }

        List<String> names = new ArrayList<>(shards.size());
        for (WeaviateShard shard : shards) {
            names.add(shard.getShardName());
        }

        if (names.size() > 1 && !UIUtils.confirmAction(
            HandlerUtil.getActiveShell(event),
            "Shard status",
            MessageFormat.format("Set {0} shards of \"{1}\" to {2}?",
                names.size(), collection, target.name()))
        ) {
            return null;
        }

        if (!(shards.get(0).getDataSource() instanceof WeaviateDataSource dataSource)) {
            return null;
        }
        String targetCollection = collection;
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    dataSource.setShardStatus(monitor, targetCollection, names, target);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot set shard status", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                "Shard status", "Failed to set the shard status", e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }

        // The server accepted it, so record it on the shards themselves: their labels are built
        // from this, and the group's summary counts it. Re-reading from the server would say the
        // same thing at the cost of a round trip, and refreshing the node would rebuild the whole
        // shard list to change one word in it.
        for (WeaviateShard shard : shards) {
            shard.setShardStatus(target);
        }
        if (refreshSubject != null) {
            DBUtils.fireObjectUpdate(refreshSubject);
            DBUtils.fireObjectUpdate(refreshSubject.getParentObject());
        }
        return null;
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            return;
        }
        ISelection selection = window.getSelectionService().getSelection();
        WeaviateShardStatus target = targetFor(selection);
        if (target == null) {
            return;
        }
        if (isGroup()) {
            WeaviateShardGroup group = selectedGroup(selection);
            int count = group == null ? 0 : group.getShardsToChange(target).size();
            element.setText(MessageFormat.format("Set All Shards READY ({0} to change)", count));
        } else {
            int count = selectedShards(selection).size();
            element.setText(count == 1
                ? "Set Shard " + target.name()
                : MessageFormat.format("Set {0} Shards {1}", count, target.name()));
        }
        element.setIcon(DBeaverIcons.getImageDescriptor(
            target == WeaviateShardStatus.READY ? UIIcon.BULLET_GREEN : UIIcon.BULLET_BLACK));
    }
}
