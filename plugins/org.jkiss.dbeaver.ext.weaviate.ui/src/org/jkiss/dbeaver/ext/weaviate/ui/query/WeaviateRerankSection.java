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
package org.jkiss.dbeaver.ext.weaviate.ui.query;

import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQueryMode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQuerySpec;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRerankSpec;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Rerank section, added to each near_* mode's field panel.
 * <p>
 * Per mode rather than shared, because it belongs to one mode's inputs -- and only to some of
 * them: client 6.3.1 exposes rerank solely on the vector-search builders, so BM25 and Hybrid,
 * which the server could rerank, have nowhere to attach it. The section is added only where it
 * can work rather than shown disabled everywhere.
 */
public class WeaviateRerankSection {

    private static final Log log = Log.getLog(WeaviateRerankSection.class);

    private final WeaviateQueryPanelContext context;

    private final Map<WeaviateQueryMode, Composite> groups = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Combo> propertyCombos = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Text> queryFields = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Label> moduleLabels = new EnumMap<>(WeaviateQueryMode.class);

    public WeaviateRerankSection(@NotNull WeaviateQueryPanelContext context) {
        this.context = context;
    }

    /** Adds the Rerank section to one mode's field panel. */
    public void addTo(@NotNull Composite c, @NotNull WeaviateQueryMode mode) {
        Composite group = context.createSection(
            c, WeaviateUIMessages.query_rerank, "rerank." + mode.name(), 2, false);
        group.setToolTipText(WeaviateUIMessages.query_rerank_tip);
        ((GridData) group.getParent().getLayoutData()).horizontalSpan = 2;

        new Label(group, SWT.NONE).setText(WeaviateUIMessages.query_rerank_property);
        Combo propertyCombo = new Combo(group, SWT.READ_ONLY);
        GridData pcGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        pcGd.widthHint = 160;
        propertyCombo.setLayoutData(pcGd);
        propertyCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateCount(mode);
            }
        });

        new Label(group, SWT.NONE).setText(WeaviateUIMessages.query_rerank_query);
        Text queryField = new Text(group, SWT.BORDER);
        queryField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        queryField.setMessage(WeaviateUIMessages.query_rerank_query_hint);
        queryField.addListener(SWT.DefaultSelection, e -> context.runQuery());

        Label moduleLabel = new Label(group, SWT.WRAP);
        GridData mlGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        mlGd.horizontalSpan = 2;
        moduleLabel.setLayoutData(mlGd);

        groups.put(mode, group);
        propertyCombos.put(mode, propertyCombo);
        queryFields.put(mode, queryField);
        moduleLabels.put(mode, moduleLabel);
    }

    /**
     * Refill the rerank property pickers and the module hint from the collection the panel is
     * bound to. "(none)" leads each list so a chosen property can be un-chosen.
     */
    public void refresh() {
        List<String> properties = context.currentPropertyNames();
        String moduleHint = moduleHint();
        for (WeaviateQueryMode mode : propertyCombos.keySet()) {
            Combo combo = propertyCombos.get(mode);
            if (combo == null || combo.isDisposed()) {
                continue;
            }
            String selected = combo.getText();
            combo.removeAll();
            combo.add(WeaviateUIMessages.query_rerank_none);
            for (String name : properties) {
                combo.add(name);
            }
            int idx = selected.isEmpty() ? 0 : Math.max(0, combo.indexOf(selected));
            combo.select(idx);
            Label moduleLabel = moduleLabels.get(mode);
            if (moduleLabel != null && !moduleLabel.isDisposed()) {
                moduleLabel.setText(moduleHint);
            }
            updateCount(mode);
        }
    }

    /**
     * The rerank request the given mode's section describes, or null for none.
     *
     * @throws IllegalArgumentException when a rerank query was typed but no property picked --
     *                                  the query alone cannot be sent, and dropping it silently
     *                                  would run a different search than the one on screen
     */
    @Nullable
    public WeaviateRerankSpec currentRerank(@NotNull WeaviateQueryMode mode) {
        if (!mode.supportsRerank()) {
            return null;
        }
        String property = selectedProperty(mode);
        Text queryField = queryFields.get(mode);
        String query = queryField == null || queryField.isDisposed() ? "" : queryField.getText();
        if (property == null) {
            if (!query.isBlank()) {
                throw new IllegalArgumentException(WeaviateUIMessages.query_rerank_property_required);
            }
            return null;
        }
        return new WeaviateRerankSpec(property, query);
    }

    public void loadFrom(@NotNull WeaviateQuerySpec spec) {
        WeaviateRerankSpec rerank = spec.getRerank();
        for (WeaviateQueryMode mode : propertyCombos.keySet()) {
            Combo combo = propertyCombos.get(mode);
            Text queryField = queryFields.get(mode);
            if (combo == null || combo.isDisposed() || queryField == null || queryField.isDisposed()) {
                continue;
            }
            if (rerank == null) {
                combo.select(0);
                queryField.setText("");
            } else {
                int idx = combo.indexOf(rerank.getProperty());
                combo.select(Math.max(0, idx));
                queryField.setText(rerank.getQuery() == null ? "" : rerank.getQuery());
            }
            updateCount(mode);
        }
    }

    private void updateCount(@NotNull WeaviateQueryMode mode) {
        context.setSectionCount(groups.get(mode), WeaviateUIMessages.query_rerank,
            selectedProperty(mode) == null ? 0 : 1);
    }

    /** The picked rerank property, or null while "(none)" is selected. */
    @Nullable
    private String selectedProperty(@NotNull WeaviateQueryMode mode) {
        Combo combo = propertyCombos.get(mode);
        if (combo == null || combo.isDisposed() || combo.getSelectionIndex() <= 0) {
            return null;
        }
        return combo.getText();
    }

    /**
     * Name the collection's reranker module, or warn that there is none -- a reranked query then
     * fails, and better the section says so than the server's error explains it after the fact.
     */
    @NotNull
    private String moduleHint() {
        WeaviateCollection collection = context.currentCollection();
        if (collection == null) {
            return "";
        }
        try {
            List<String> kinds = new ArrayList<>();
            for (var reranker : collection.getRerankers(new VoidProgressMonitor())) {
                kinds.add(reranker.getName());
            }
            return kinds.isEmpty()
                ? WeaviateUIMessages.query_rerank_no_module
                : NLS.bind(WeaviateUIMessages.query_rerank_module, String.join(", ", kinds));
        } catch (Exception e) {
            log.debug("Failed to read reranker modules", e);
            return "";
        }
    }
}
