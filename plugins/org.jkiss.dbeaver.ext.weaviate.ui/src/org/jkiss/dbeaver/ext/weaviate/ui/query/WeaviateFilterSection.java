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

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateColumns;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateFilterOperator;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateFilterRow;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateFilterTranslator;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateProperty;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQuerySpec;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

import java.util.ArrayList;
import java.util.List;

/**
 * The Filters section: any number of property/operator/value rows, matched all or any.
 * <p>
 * Shared across modes rather than per mode, like Group by: a filter narrows whatever search is
 * selected. Collapsed by default and built last of the stacked sections, because a query is
 * usually run without filters and the rows are the panel's tallest block when they are used.
 */
public class WeaviateFilterSection {

    private final WeaviateQueryPanelContext context;

    private Composite filtersGroup;
    private Composite filterRowsHolder;
    private Button filterAndRadio;
    private Button filterOrRadio;

    private final List<FilterRowUi> filterRowUis = new ArrayList<>();

    public WeaviateFilterSection(@NotNull WeaviateQueryPanelContext context) {
        this.context = context;
    }

    public void createControls(@NotNull Composite parent) {
        filtersGroup = context.createSection(
            parent, WeaviateUIMessages.query_filters, "filters", 1, false);

        Composite header = new Composite(filtersGroup, SWT.NONE);
        GridLayout hl = new GridLayout(4, false);
        hl.marginWidth = 0;
        hl.marginHeight = 0;
        header.setLayout(hl);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(header, SWT.NONE).setText(WeaviateUIMessages.query_match);
        filterAndRadio = new Button(header, SWT.RADIO);
        filterAndRadio.setText(WeaviateUIMessages.query_match_all);
        filterAndRadio.setSelection(true);
        filterOrRadio = new Button(header, SWT.RADIO);
        filterOrRadio.setText(WeaviateUIMessages.query_match_any);

        Button addRow = new Button(header, SWT.PUSH);
        addRow.setText("+ Add filter");
        addRow.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        addRow.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                addRow(null);
            }
        });

        filterRowsHolder = new Composite(filtersGroup, SWT.NONE);
        GridLayout rl = new GridLayout(1, false);
        rl.marginWidth = 0;
        rl.marginHeight = 0;
        rl.verticalSpacing = 3;
        filterRowsHolder.setLayout(rl);
        filterRowsHolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    }

    public void updateCount() {
        context.setSectionCount(filtersGroup, WeaviateUIMessages.query_filters, filterRowUis.size());
    }

    /** The rows as the spec wants them, skipping any that name no property. */
    @NotNull
    public List<WeaviateFilterRow> collectRows() {
        List<WeaviateFilterRow> rows = new ArrayList<>(filterRowUis.size());
        for (FilterRowUi ui : filterRowUis) {
            WeaviateFilterRow row = ui.toRow();
            if (row != null) rows.add(row);
        }
        return rows;
    }

    /** True when the rows are OR-ed rather than AND-ed. */
    public boolean isAnyFilter() {
        return filterOrRadio != null && filterOrRadio.getSelection();
    }

    public void loadFrom(@NotNull WeaviateQuerySpec spec) {
        if (filterRowsHolder == null || filterRowsHolder.isDisposed()) {
            return;
        }
        clearRows();
        for (WeaviateFilterRow row : spec.getFilterRows()) {
            addRow(row);
        }
        if (filterAndRadio != null) {
            filterAndRadio.setSelection(!spec.isAnyFilter());
            filterOrRadio.setSelection(spec.isAnyFilter());
        }
    }

    private void addRow(@Nullable WeaviateFilterRow seed) {
        if (filterRowsHolder == null || filterRowsHolder.isDisposed()) return;
        FilterRowUi ui = new FilterRowUi(filterRowsHolder, filterPropertyNames(), seed);
        filterRowUis.add(ui);
        filterRowsHolder.layout(true, true);
        updateCount();
    }

    private void clearRows() {
        for (FilterRowUi ui : new ArrayList<>(filterRowUis)) {
            ui.dispose();
        }
        filterRowUis.clear();
        // Needed on its own account: loading a spec with no filters at all clears the rows without
        // adding any, and the title would otherwise keep the previous spec's count.
        updateCount();
    }

    /**
     * Property names for the filter rows: the collection's own, plus the synthetic columns the
     * grid shows.
     * <p>
     * Kept apart from {@code currentPropertyNames()}, which feeds the BM25, generative and rerank
     * pickers -- none of those can do anything with a uuid or a timestamp. The translator has
     * always known how to filter these three; until now nothing offered them.
     */
    @NotNull
    private List<String> filterPropertyNames() {
        // The collection's own properties lead. A new row selects the first entry, and defaulting
        // that to a synthetic column would quietly filter on something nobody chose.
        List<String> names = new ArrayList<>(context.currentPropertyNames());
        names.add(WeaviateFilterTranslator.UUID_COLUMN);
        names.add(WeaviateColumns.CREATED);
        names.add(WeaviateColumns.UPDATED);
        return names;
    }

    private DBPDataKind dataKindForProperty(@NotNull String propertyName) {
        // The synthetic columns are not in the schema, so their type has to be stated here or
        // the value would be coerced as text and never match.
        if (WeaviateColumns.CREATED.equals(propertyName)
            || WeaviateColumns.UPDATED.equals(propertyName)
        ) {
            return DBPDataKind.DATETIME;
        }
        if (WeaviateFilterTranslator.UUID_COLUMN.equalsIgnoreCase(propertyName)) {
            return DBPDataKind.STRING;
        }
        WeaviateCollection collection = context.currentCollection();
        if (collection == null) return DBPDataKind.STRING;
        try {
            for (WeaviateProperty p : collection.getProperties(new VoidProgressMonitor())) {
                if (p.getName().equals(propertyName)) return p.getDataKind();
            }
        } catch (DBException ignored) {
            // fall through
        }
        return DBPDataKind.STRING;
    }

    /**
     * SWT controls for a single filter row. Owns its container Composite so disposing it
     * cleanly removes the row from the layout.
     */
    private final class FilterRowUi {
        private final Composite container;
        private final Combo propertyCombo;
        private final Combo opCombo;
        private final Text valueField;

        FilterRowUi(
            @NotNull Composite parent,
            @NotNull List<String> propertyNames,
            @Nullable WeaviateFilterRow seed
        ) {
            container = new Composite(parent, SWT.NONE);
            GridLayout gl = new GridLayout(4, false);
            gl.marginWidth = 0;
            gl.marginHeight = 0;
            container.setLayout(gl);
            container.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            propertyCombo = new Combo(container, SWT.READ_ONLY);
            for (String name : propertyNames) propertyCombo.add(name);
            GridData pcGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
            pcGd.widthHint = 140;
            propertyCombo.setLayoutData(pcGd);

            opCombo = new Combo(container, SWT.READ_ONLY);
            for (WeaviateFilterOperator op : WeaviateFilterRow.SUPPORTED_OPERATORS) {
                opCombo.add(op.getLabel());
            }
            opCombo.select(0);
            opCombo.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    syncValueEnabled();
                }
            });

            valueField = new Text(container, SWT.BORDER);
            valueField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            valueField.setMessage("value");

            Button remove = new Button(container, SWT.PUSH | SWT.FLAT);
            remove.setText("✕");
            remove.setToolTipText(WeaviateUIMessages.query_remove_filter);
            remove.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    dispose();
                    filterRowUis.remove(FilterRowUi.this);
                    filterRowsHolder.layout(true, true);
                    updateCount();
                }
            });

            if (seed != null) {
                propertyCombo.setText(seed.property());
                int idx = WeaviateFilterRow.SUPPORTED_OPERATORS.indexOf(seed.operator());
                opCombo.select(Math.max(0, idx));
                if (seed.rawValue() != null) valueField.setText(seed.rawValue());
            } else if (!propertyNames.isEmpty()) {
                propertyCombo.select(0);
            }
            syncValueEnabled();
        }

        private void syncValueEnabled() {
            WeaviateFilterOperator op = currentOperator();
            valueField.setEnabled(op.takesValue());
            // The cell holds one value, a comma-separated list, or a low/high pair depending on
            // the operator, so the hint has to say which.
            valueField.setMessage(op.takesList() ? "comma-separated" : "value");
        }

        private WeaviateFilterOperator currentOperator() {
            int idx = opCombo.getSelectionIndex();
            if (idx < 0) idx = 0;
            return WeaviateFilterRow.SUPPORTED_OPERATORS.get(idx);
        }

        @Nullable
        WeaviateFilterRow toRow() {
            String property = propertyCombo.getText();
            if (property == null || property.isBlank()) return null;
            WeaviateFilterOperator op = currentOperator();
            String raw = valueField.getText();
            DBPDataKind kind = dataKindForProperty(property);
            return new WeaviateFilterRow(property, op, raw, kind);
        }

        void dispose() {
            if (!container.isDisposed()) container.dispose();
        }
    }
}
