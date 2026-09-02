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
import org.eclipse.osgi.util.NLS;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateAlias;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateAliasDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateAliasRefresh;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateChangeConfirmDialog;
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
 * Repointing and deleting aliases picked in the tree.
 * <p>
 * Creating one lives in {@link WeaviateAliasFolderHandler}, for the reason given on the role
 * handlers: {@code setEnabled} cannot see which {@code operation} an invocation carries, so a
 * handler spanning both scopes would have to enable every operation wherever any applies, and
 * "Delete Alias" would appear on the folder where there is no alias to delete.
 * <p>
 * Both operations here apply to any alias row, so one command with an {@code operation} parameter
 * is right -- the split that was needed for replication was needed because <em>enablement</em>
 * differed, not because the labels did.
 * <p>
 * Delete takes a multi-selection. Repointing is about one alias by its nature: the whole point is
 * which collection this particular name resolves to.
 */
public class WeaviateAliasHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviateAliasHandler.class);

    private static final String PARAM_OPERATION = "operation";
    private static final String OP_RETARGET = "retarget";
    private static final String OP_DELETE = "delete";

    @Override
    public void setEnabled(Object evaluationContext) {
        // Shape only, and computed here rather than in an <enabledWhen>: see WeaviateRoleHandler.
        ISelection selection = WeaviateAliasNodes.selectionOf(evaluationContext);
        setBaseEnabled(selection != null
            && WeaviateAliasNodes.dataSourceOf(selection) != null
            && !WeaviateAliasNodes.selectedAliases(selection).isEmpty());
    }

    @Override
    public Object execute(ExecutionEvent event) {
        String operation = event.getParameter(PARAM_OPERATION);
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        WeaviateDataSource dataSource = WeaviateAliasNodes.dataSourceOf(selection);
        List<WeaviateAlias> aliases = WeaviateAliasNodes.selectedAliases(selection);
        if (dataSource == null || aliases.isEmpty()) {
            return null;
        }
        if (OP_DELETE.equals(operation)) {
            deleteAliases(event, dataSource, aliases);
            return null;
        }
        if (!OP_RETARGET.equals(operation)) {
            return null;
        }
        if (aliases.size() > 1) {
            DBWorkbench.getPlatformUI().showMessageBox(WeaviateUIMessages.alias_title,
                WeaviateUIMessages.alias_retarget_one_only, true);
            return null;
        }
        retarget(event, dataSource, aliases.get(0));
        return null;
    }

    private void retarget(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @NotNull WeaviateAlias alias
    ) {
        List<String> collections = collectionNames(dataSource);
        WeaviateAliasDialog dialog = WeaviateAliasDialog.forRetarget(
            HandlerUtil.getActiveShell(event),
            alias.getName(), alias.getTargetCollection(), collections);
        if (dialog.open() != IDialogConstants.OK_ID) {
            return;
        }
        String target = dialog.getTargetCollection();
        if (target == null) {
            return;
        }
        run(WeaviateUIMessages.alias_title, monitor -> {
            dataSource.retargetAlias(monitor, alias.getName(), target);
            WeaviateAliasRefresh.after(monitor, dataSource);
        });
    }

    /**
     * Deleting is confirmed, unlike creating.
     * <p>
     * Dropping an alias is invisible from the collection's side -- the data is untouched and the
     * tree loses one row -- while every reader querying by that name starts failing. That is the
     * shape of change worth asking about.
     */
    private void deleteAliases(
        @NotNull ExecutionEvent event,
        @NotNull WeaviateDataSource dataSource,
        @NotNull List<WeaviateAlias> aliases
    ) {
        List<WeaviateChangeConfirmDialog.Row> rows = new ArrayList<>(aliases.size());
        for (WeaviateAlias alias : aliases) {
            rows.add(WeaviateChangeConfirmDialog.Row.of(alias, WeaviateUIMessages.alias_row_type,
                NLS.bind(WeaviateUIMessages.alias_delete_outcome, alias.getTargetCollection()),
                true));
        }
        WeaviateChangeConfirmDialog confirm = new WeaviateChangeConfirmDialog(
            HandlerUtil.getActiveShell(event),
            WeaviateUIMessages.alias_delete_title,
            aliases.size() == 1
                ? NLS.bind(WeaviateUIMessages.alias_delete_prompt_one, aliases.get(0).getName())
                : NLS.bind(WeaviateUIMessages.alias_delete_prompt_many, aliases.size()),
            rows,
            WeaviateUIMessages.alias_button_delete);
        if (confirm.open() != IDialogConstants.OK_ID) {
            return;
        }
        List<String> names = new ArrayList<>(aliases.size());
        for (WeaviateAlias alias : aliases) {
            names.add(alias.getName());
        }
        // Collected in the worker and reported after it, not inside: a message box belongs on the
        // UI thread, and this runs off it.
        List<String> gone = new ArrayList<>();
        run(WeaviateUIMessages.alias_delete_title, monitor -> {
            for (String name : names) {
                if (!dataSource.deleteAlias(monitor, name)) {
                    // The client turns the server's 404 into false rather than an exception, so an
                    // alias another session has already dropped is reported rather than raised.
                    gone.add(name);
                }
            }
            WeaviateAliasRefresh.after(monitor, dataSource);
        });
        if (!gone.isEmpty()) {
            DBWorkbench.getPlatformUI().showMessageBox(WeaviateUIMessages.alias_delete_title,
                NLS.bind(WeaviateUIMessages.alias_delete_already_gone, String.join(", ", gone)),
                false);
        }
    }

    /** The collections already in memory; a repoint is offered nothing it cannot see. */
    @NotNull
    private static List<String> collectionNames(@NotNull WeaviateDataSource dataSource) {
        List<WeaviateCollection> loaded = dataSource.getLoadedCollections();
        List<String> names = new ArrayList<>();
        if (loaded != null) {
            for (WeaviateCollection collection : loaded) {
                names.add(collection.getName());
            }
        }
        return names;
    }

    @FunctionalInterface
    private interface Work {
        void run(org.jkiss.dbeaver.model.runtime.DBRProgressMonitor monitor) throws DBException;
    }

    private void run(@NotNull String title, @NotNull Work work) {
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    work.run(monitor);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error(title, e.getTargetException());
            DBWorkbench.getPlatformUI().showError(title,
                e.getTargetException().getMessage(), e.getTargetException());
        } catch (InterruptedException e) {
            // Cancelled.
        }
    }

    /**
     * One command serves both entries, so without this they would share the icon from
     * {@code <commandImages>} and read as the same action twice. Delete also names how many
     * aliases it would remove, from what the tree already holds.
     */
    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        Object operation = parameters == null ? null : parameters.get(PARAM_OPERATION);
        if (!(operation instanceof String op)) {
            return;
        }
        if (OP_RETARGET.equals(op)) {
            element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.EDIT));
            element.setText(WeaviateUIMessages.alias_action_retarget);
            return;
        }
        if (!OP_DELETE.equals(op)) {
            return;
        }
        element.setIcon(DBeaverIcons.getImageDescriptor(UIIcon.DELETE));
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            element.setText(WeaviateUIMessages.alias_action_delete_one);
            return;
        }
        int count = WeaviateAliasNodes
            .selectedAliases(window.getSelectionService().getSelection()).size();
        element.setText(count > 1
            ? NLS.bind(WeaviateUIMessages.alias_action_delete_many, count)
            : WeaviateUIMessages.alias_action_delete_one);
    }
}
