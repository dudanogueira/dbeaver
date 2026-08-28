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
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateModule;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;
import org.jkiss.utils.CommonUtils;

import java.text.MessageFormat;
import java.util.Map;

/**
 * Opens the documentation a module reports for itself.
 * <p>
 * Weaviate hands back a {@code documentationHref} per module in {@code /v1/meta}, which the
 * plugin has always read and never offered to open -- it reaches the Properties view as a column
 * and stops there.
 * <p>
 * Deliberately not routed through {@link org.jkiss.dbeaver.ext.weaviate.model.WeaviateDocTopics},
 * which is the other half of "Read Docs" in this plugin. That table maps a navigator folder to a
 * page under {@code docs.weaviate.io/weaviate/}, and a test pins every entry in it to that prefix.
 * Module hrefs mostly point somewhere else entirely -- platform.openai.com, docs.anthropic.com,
 * jina.ai -- because a module's documentation is usually the vendor's, not Weaviate's. The URL
 * therefore comes off the object.
 */
public class WeaviateOpenModuleDocsHandler extends AbstractHandler implements IElementUpdater {

    @Nullable
    private static WeaviateModule selectedModule(@Nullable ISelection selection) {
        if (selection == null) {
            return null;
        }
        DBNNode node = NavigatorUtils.getSelectedNode(selection);
        return node instanceof DBNDatabaseNode databaseNode
            && databaseNode.getObject() instanceof WeaviateModule module
            ? module : null;
    }

    @Nullable
    private static ISelection selectionFrom(Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? sel : null;
    }

    @Nullable
    private static String hrefOf(@Nullable ISelection selection) {
        WeaviateModule module = selectedModule(selection);
        return module == null ? null : CommonUtils.nullIfEmpty(module.getDocumentationHref());
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        // Applicability, so the entry is hidden rather than greyed (plugin.xml pairs this with
        // checkEnabled). Whether a module documents itself is the module's own business and no
        // newer server makes it true, so there is nothing to tell the user by showing it disabled.
        // On this server that hides it for backup-filesystem and ref2vec-centroid.
        setBaseEnabled(hrefOf(selectionFrom(evaluationContext)) != null);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        String href = hrefOf(HandlerUtil.getCurrentSelection(event));
        if (href != null) {
            UIUtils.openWebBrowser(href);
        }
        return null;
    }

    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            return;
        }
        WeaviateModule module = selectedModule(window.getSelectionService().getSelection());
        if (module != null) {
            // Same phrasing as the folder version, so the two read as one action.
            element.setText(
                MessageFormat.format(WeaviateUIMessages.docs_read_docs_on, module.getName()));
        }
    }
}
