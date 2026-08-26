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
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.ISources;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateProperty;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateServerFeature;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateTokenizePreviewDialog;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shows how a text property's analyzer splits text.
 * <p>
 * Two different gates, answered two different ways:
 * <ul>
 *   <li><b>Not a text property</b> -- the item is hidden. An int is not tokenized and never will
 *       be, so a greyed entry on it is noise. Enablement is computed here rather than in
 *       {@code <enabledWhen>} because the condition is the property's tokenization setting, which
 *       no built-in property tester exposes, and a tester shipped from this lazily-activated
 *       bundle would evaluate as NOT_LOADED before the bundle is active -- the same reasoning
 *       {@link WeaviateReadDocsHandler} records.</li>
 *   <li><b>Server too old</b> -- the item stays, disabled, with the required version in its label.
 *       The point of a version gate is to tell you something you do not know; hiding it teaches
 *       nothing. The wording matches weaviate-studio's, which writes a full three-part version
 *       after an em-dash.</li>
 * </ul>
 * Note the version gate is <em>not</em> applied to the tokenizer itself. Whether a server has
 * {@code gse} or {@code kagome_ja} is a build flag rather than a release feature, so only the
 * server can answer that -- it comes back as a 422 the dialog renders inline.
 */
public class WeaviatePreviewTokenizationHandler extends AbstractHandler implements IElementUpdater {

    private static final Log log = Log.getLog(WeaviatePreviewTokenizationHandler.class);

    @Override
    public Object execute(ExecutionEvent event) {
        WeaviateProperty property = selectedProperty(HandlerUtil.getCurrentSelection(event));
        if (property == null || !(property.getParentObject() instanceof WeaviateCollection collection)) {
            return null;
        }
        Shell shell = HandlerUtil.getActiveShell(event);

        // The dialog switches between properties without reopening, so it needs the collection's
        // tokenizable set. Resolved here rather than inside the dialog: reading the schema is a
        // round-trip, and the established split is that handlers do the I/O under a progress
        // monitor and dialogs receive finished data.
        Map<String, String> tokenizable = new LinkedHashMap<>();
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    for (WeaviateProperty p : collection.getProperties(monitor)) {
                        if (p.getTokenization() != null) {
                            tokenizable.put(p.getName(), p.getTokenization());
                        }
                    }
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot read properties of " + collection.getName(), e.getTargetException());
            DBWorkbench.getPlatformUI().showError(WeaviateUIMessages.tokenize_error_title,
                e.getTargetException().getMessage(), e.getTargetException());
            return null;
        } catch (InterruptedException e) {
            return null;
        }
        // The property that was right-clicked is tokenizable by construction -- setEnabled checked
        // -- but a schema read racing a change could still miss it, and an absent selection would
        // silently land on whatever sits at index 0.
        tokenizable.putIfAbsent(property.getName(), property.getTokenization());

        new WeaviateTokenizePreviewDialog(shell, collection, property.getName(), tokenizable).open();
        return null;
    }

    @Override
    public void setEnabled(Object evaluationContext) {
        WeaviateProperty property = selectedProperty(selectionFrom(evaluationContext));
        // Applicability only. Tokenization is null on anything that is not a text property, which
        // is exactly the set the server refuses, and it will never become true -- so the menu
        // entry is hidden rather than greyed (plugin.xml pairs this with checkEnabled).
        //
        // The version is deliberately NOT part of this. An old server is a "not yet", and hiding
        // a "not yet" teaches nobody anything; updateElement labels it instead.
        setBaseEnabled(property != null && property.getTokenization() != null);
    }

    /**
     * Rewrites the menu label to name the required version when the server is too old.
     * <p>
     * The command stays clickable on purpose. The dialog reports failures inline, and on a
     * pre-1.37 server the tokenize call answers {@code 404 path /v1/tokenize was not found} --
     * which explains the situation better than a greyed-out control could, and matches the rule
     * that an unknown version is allowed through for the same reason.
     */
    @Override
    public void updateElement(UIElement element, @SuppressWarnings("rawtypes") Map parameters) {
        // No evaluation context here, so the selection comes from the window's selection service,
        // the same route WeaviateReadDocsHandler takes to build its per-selection label.
        IWorkbenchWindow window = element.getServiceLocator().getService(IWorkbenchWindow.class);
        if (window == null || window.getSelectionService() == null) {
            return;
        }
        WeaviateProperty property = selectedProperty(window.getSelectionService().getSelection());
        if (property != null && !isSupported(property)) {
            element.setText(MessageFormat.format(WeaviateUIMessages.tokenize_requires_version,
                WeaviateServerFeature.TOKENIZE.getMinVersion()));
        }
    }

    /**
     * Whether the connected server has the tokenize endpoint. An unknown version answers yes --
     * see {@code WeaviateVersions}: a wrong guess here costs a legible 404 from the server, while
     * the opposite guess costs a working feature hidden with no explanation.
     */
    private static boolean isSupported(@NotNull WeaviateProperty property) {
        if (!(property.getParentObject() instanceof WeaviateCollection collection)) {
            return false;
        }
        if (!(collection.getDataSource() instanceof WeaviateDataSource dataSource)) {
            return true;
        }
        return dataSource.supports(WeaviateServerFeature.TOKENIZE);
    }

    @Nullable
    private static ISelection selectionFrom(@Nullable Object evaluationContext) {
        if (!(evaluationContext instanceof IEvaluationContext context)) {
            return null;
        }
        Object selection = context.getVariable(ISources.ACTIVE_CURRENT_SELECTION_NAME);
        return selection instanceof ISelection sel ? sel : null;
    }

    /**
     * The selected property, or null.
     * <p>
     * The Properties folder is backed by {@code attributes}, which includes the synthetic uuid
     * column alongside the real ones -- hence the type test rather than a cast.
     */
    @Nullable
    private static WeaviateProperty selectedProperty(@Nullable ISelection selection) {
        if (selection == null) {
            return null;
        }
        DBNNode node = NavigatorUtils.getSelectedNode(selection);
        if (node instanceof DBNDatabaseNode databaseNode
            && databaseNode.getObject() instanceof WeaviateProperty property) {
            return property;
        }
        return null;
    }
}
