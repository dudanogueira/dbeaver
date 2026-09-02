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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateAliasDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateAliasRefresh;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Creating an alias, from either Aliases folder.
 * <p>
 * One handler for both, unlike the role and user folders which have one each. The difference
 * between the two folders here is an argument, not an operation: a collection's own folder already
 * knows the target and the connection-wide one does not, and {@code setEnabled} can tell them
 * apart without needing a command parameter to do it.
 */
public class WeaviateAliasFolderHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateAliasFolderHandler.class);

    @Override
    public void setEnabled(Object evaluationContext) {
        ISelection selection = WeaviateAliasNodes.selectionOf(evaluationContext);
        setBaseEnabled(selection != null
            && WeaviateAliasNodes.isSingleAliasFolder(selection)
            && WeaviateAliasNodes.dataSourceOf(selection) != null);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = WeaviateAliasNodes.dataSourceOf(selection);
        if (dataSource == null) {
            return null;
        }
        String presetTarget = WeaviateAliasNodes.targetCollectionOf(selection);
        List<String> collections = collectionNames(dataSource);
        if (collections.isEmpty()) {
            // Nothing to choose from yet. That is not the same as "no collections": the tree may
            // simply never have been expanded, so ask the server before concluding anything.
            collections = fetchCollectionNames(dataSource);
        }
        if (presetTarget == null && collections.isEmpty()) {
            DBWorkbench.getPlatformUI().showError(WeaviateUIMessages.alias_create_title,
                WeaviateUIMessages.alias_no_collections);
            return null;
        }
        List<String> choices = collections;

        WeaviateAliasDialog dialog = WeaviateAliasDialog.forCreate(
            HandlerUtil.getActiveShell(event), choices, presetTarget);
        if (dialog.open() != IDialogConstants.OK_ID) {
            return null;
        }
        String alias = dialog.getAliasName();
        String target = dialog.getTargetCollection();
        if (target == null) {
            return null;
        }
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    dataSource.createAlias(monitor, alias, target);
                    WeaviateAliasRefresh.after(monitor, dataSource);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot create alias " + alias, e.getTargetException());
            DBWorkbench.getPlatformUI().showError(WeaviateUIMessages.alias_create_title,
                e.getTargetException().getMessage(), e.getTargetException());
        } catch (InterruptedException e) {
            // Cancelled.
        }
        return null;
    }

    /** The collections already in memory, without asking the server. */
    @NotNull
    private static List<String> collectionNames(@NotNull WeaviateDataSource dataSource) {
        return names(dataSource.getLoadedCollections());
    }

    /** The collections, asking the server if it has not been asked yet. */
    @NotNull
    private static List<String> fetchCollectionNames(@NotNull WeaviateDataSource dataSource) {
        List<String> fetched = new ArrayList<>();
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    fetched.addAll(names(dataSource.getCollections(monitor)));
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.debug("Cannot list collections to offer as an alias target",
                e.getTargetException());
        } catch (InterruptedException e) {
            // Cancelled.
        }
        return fetched;
    }

    @NotNull
    private static List<String> names(@Nullable List<WeaviateCollection> collections) {
        if (collections == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>(collections.size());
        for (WeaviateCollection collection : collections) {
            names.add(collection.getName());
        }
        return names;
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.ADD));
    }
}
