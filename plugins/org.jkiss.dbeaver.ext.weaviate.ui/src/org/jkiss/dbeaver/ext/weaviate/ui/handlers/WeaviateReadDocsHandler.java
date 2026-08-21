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
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.eclipse.core.expressions.IEvaluationContext;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDocTopics;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;
import org.jkiss.utils.CommonUtils;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Opens the Weaviate documentation for the selected collection config folder.
 * <p>
 * The menu label is built per selection rather than fixed, so the folder reads
 * "Read Docs on Multi-Tenancy" rather than something generic. The topic name is the
 * folder's own display name, which comes from the {@code label=} already declared in
 * the model bundle's {@code plugin.xml} -- there is deliberately no second list of
 * human-readable topic names to keep in sync with the tree.
 * <p>
 * Enablement is computed here rather than by an {@code <enabledWhen>} expression. The
 * condition is "this folder's meta id has a documentation topic", which no built-in
 * property tester exposes; and a tester contributed from this bundle would be evaluated
 * as {@code NOT_LOADED} while the bundle is still lazy, so the item would never appear
 * and the bundle would never be activated to make it appear. The navigator rebuilds its
 * context menu on every right-click (see {@code NavigatorUtils.addStandardMenuItem}), so
 * {@link #setEnabled} is re-run per selection.
 */
public class WeaviateReadDocsHandler extends AbstractHandler implements IElementUpdater {

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        String url = docsUrlFor(NavigatorUtils.getSelectedNode(selection));
        if (url != null) {
            UIUtils.openWebBrowser(url);
        }
        return null;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        setBaseEnabled(docsUrlFor(selectionFrom(evaluationContext)) != null);
    }

    @Nullable
    private static DBNNode selectionFrom(Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? NavigatorUtils.getSelectedNode(sel) : null;
    }

    @Nullable
    private static String docsUrlFor(@Nullable DBNNode node) {
        return node instanceof DBNDatabaseFolder folder
            ? WeaviateDocTopics.urlFor(folder.getNodeId())
            : null;
    }

    @Override
    public void updateElement(UIElement element, Map parameters) {
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            return;
        }
        DBNNode node = NavigatorUtils.getSelectedNode(window.getSelectionService().getSelection());
        if (!(node instanceof DBNDatabaseFolder folder)) {
            return;
        }
        String topic = folder.getNodeDisplayName();
        if (!CommonUtils.isEmpty(topic)) {
            element.setText(MessageFormat.format(WeaviateUIMessages.docs_read_docs_on, topic));
        }
    }
}
