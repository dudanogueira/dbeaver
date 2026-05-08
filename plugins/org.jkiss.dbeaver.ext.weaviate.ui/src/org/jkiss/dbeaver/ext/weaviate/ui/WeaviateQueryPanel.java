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
package org.jkiss.dbeaver.ext.weaviate.ui;

import org.eclipse.jface.action.IContributionManager;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.eclipse.swt.widgets.Group;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateFilterRow;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateHybridFusion;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateProperty;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQueryMode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQuerySpec;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateVectorParser;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.exec.DBCLogicalOperator;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetPresentation;
import org.jkiss.dbeaver.ui.controls.resultset.panel.ResultSetPanelBase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WeaviateQueryPanel extends ResultSetPanelBase {

    public static final String PANEL_ID = "weaviate-query";

    private static final Log log = Log.getLog(WeaviateQueryPanel.class);
    private static final String NO_FUSION = "(default)";

    private IResultSetPresentation presentation;

    private Combo modeCombo;
    private StackLayout fieldsLayout;
    private Composite fieldsHolder;
    private Composite emptyComposite;
    private Composite bm25Composite;
    private Text bm25QueryField;
    private org.eclipse.swt.widgets.List bm25PropertiesList;
    private Composite nearTextComposite;
    private Text nearTextQueryField;
    private Text nearTextDistanceField;
    private Composite nearVectorComposite;
    private Text nearVectorField;
    private Text nearVectorDistanceField;
    private Composite hybridComposite;
    private Text hybridQueryField;
    private Spinner hybridAlphaSpinner;
    private Combo hybridFusionCombo;
    private Composite banner;
    private Label bannerLabel;
    private Color errorBg;
    private Color infoBg;
    private Font bannerFont;
    private Group filtersGroup;
    private Composite filterRowsHolder;
    private Button filterAndRadio;
    private Button filterOrRadio;
    private final java.util.List<FilterRowUi> filterRowUis = new java.util.ArrayList<>();

    public WeaviateQueryPanel() {
    }

    @Override
    public Control createContents(IResultSetPresentation presentation, Composite parent) {
        this.presentation = presentation;

        Composite root = new Composite(parent, SWT.NONE);
        GridLayout rootLayout = new GridLayout(1, false);
        rootLayout.marginWidth = 4;
        rootLayout.marginHeight = 4;
        root.setLayout(rootLayout);

        Composite topRow = new Composite(root, SWT.NONE);
        GridLayout topLayout = new GridLayout(4, false);
        topLayout.marginWidth = 0;
        topLayout.marginHeight = 0;
        topRow.setLayout(topLayout);
        topRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(topRow, SWT.NONE).setText("Mode:");
        modeCombo = new Combo(topRow, SWT.READ_ONLY);
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            modeCombo.add(mode.getLabel());
        }
        modeCombo.select(0);
        modeCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                updateFieldVisibility();
                // A new mode means a fresh start — drop any stale error from the previous run
                // so it doesn't follow the user from one mode to another.
                dismissBanner();
            }
        });

        Button runButton = new Button(topRow, SWT.PUSH);
        runButton.setText("Run");
        runButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                runQuery();
            }
        });

        Button resetButton = new Button(topRow, SWT.PUSH);
        resetButton.setText("Reset to Fetch");
        resetButton.setToolTipText("Revert to plain Fetch and reload — useful when a search fails (e.g. missing vectorizer credentials).");
        resetButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                resetToFetch();
            }
        });

        banner = createBanner(root);

        fieldsHolder = new Composite(root, SWT.NONE);
        GridData fhGd = new GridData(SWT.FILL, SWT.TOP, true, false);
        fhGd.heightHint = 100;
        fieldsHolder.setLayoutData(fhGd);
        fieldsLayout = new StackLayout();
        fieldsHolder.setLayout(fieldsLayout);

        emptyComposite = createEmptyFields(fieldsHolder);
        bm25Composite = createBm25Fields(fieldsHolder);
        nearTextComposite = createNearTextFields(fieldsHolder);
        nearVectorComposite = createNearVectorFields(fieldsHolder);
        hybridComposite = createHybridFields(fieldsHolder);

        createFilterSection(root);

        loadSpecIntoUi();
        updateFieldVisibility();
        refreshStatusFromCollection();

        return root;
    }

    private static GridLayout twoColumnLayout() {
        GridLayout l = new GridLayout(2, false);
        l.marginWidth = 0;
        l.marginHeight = 0;
        l.verticalSpacing = 6;
        return l;
    }

    private static GridData fillFieldData() {
        return new GridData(SWT.FILL, SWT.CENTER, true, false);
    }

    private static GridData labelTopData() {
        return new GridData(SWT.LEFT, SWT.TOP, false, false);
    }

    private Composite createEmptyFields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(new GridLayout(1, false));
        Label l = new Label(c, SWT.WRAP);
        l.setText("Fetch returns rows in insertion order. Filters and sorts from the result-set toolbar still apply.");
        l.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return c;
    }

    private Composite createBm25Fields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        new Label(c, SWT.NONE).setText("Query:");
        bm25QueryField = new Text(c, SWT.BORDER);
        bm25QueryField.setLayoutData(fillFieldData());
        bm25QueryField.setMessage("Enter search text...");

        Label lbl = new Label(c, SWT.NONE);
        lbl.setText("Properties:");
        lbl.setLayoutData(labelTopData());
        bm25PropertiesList = new org.eclipse.swt.widgets.List(c, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL);
        GridData lgd = new GridData(SWT.FILL, SWT.FILL, true, true);
        lgd.heightHint = 80;
        bm25PropertiesList.setLayoutData(lgd);
        bm25PropertiesList.setToolTipText("Restrict the BM25 search to selected text properties (Cmd/Ctrl+click for multiple). Empty = all properties.");
        return c;
    }

    private Composite createNearTextFields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        new Label(c, SWT.NONE).setText("Query:");
        nearTextQueryField = new Text(c, SWT.BORDER);
        nearTextQueryField.setLayoutData(fillFieldData());
        nearTextQueryField.setMessage("Enter search text...");

        new Label(c, SWT.NONE).setText("Distance:");
        nearTextDistanceField = new Text(c, SWT.BORDER);
        nearTextDistanceField.setLayoutData(fillFieldData());
        nearTextDistanceField.setMessage("Optional max distance threshold (e.g. 0.7)");
        return c;
    }

    private Composite createNearVectorFields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        Label vl = new Label(c, SWT.NONE);
        vl.setText("Vector:");
        vl.setLayoutData(labelTopData());
        nearVectorField = new Text(c, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP);
        GridData vgd = new GridData(SWT.FILL, SWT.FILL, true, true);
        vgd.heightHint = 80;
        nearVectorField.setLayoutData(vgd);
        nearVectorField.setMessage("[0.1, 0.2, 0.3, ...] (comma-separated, brackets optional)");

        new Label(c, SWT.NONE).setText("Distance:");
        nearVectorDistanceField = new Text(c, SWT.BORDER);
        nearVectorDistanceField.setLayoutData(fillFieldData());
        nearVectorDistanceField.setMessage("Optional max distance threshold (e.g. 0.7)");
        return c;
    }

    private Composite createHybridFields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        new Label(c, SWT.NONE).setText("Query:");
        hybridQueryField = new Text(c, SWT.BORDER);
        hybridQueryField.setLayoutData(fillFieldData());
        hybridQueryField.setMessage("Enter search text...");

        new Label(c, SWT.NONE).setText("Alpha:");
        hybridAlphaSpinner = new Spinner(c, SWT.BORDER);
        hybridAlphaSpinner.setDigits(2);
        hybridAlphaSpinner.setMinimum(0);
        hybridAlphaSpinner.setMaximum(100);
        hybridAlphaSpinner.setIncrement(5);
        hybridAlphaSpinner.setSelection((int) (WeaviateQuerySpec.DEFAULT_HYBRID_ALPHA * 100));
        hybridAlphaSpinner.setToolTipText("0 = pure keyword (BM25), 1 = pure vector");

        new Label(c, SWT.NONE).setText("Fusion:");
        hybridFusionCombo = new Combo(c, SWT.READ_ONLY);
        hybridFusionCombo.add(NO_FUSION);
        for (WeaviateHybridFusion ft : WeaviateHybridFusion.values()) {
            hybridFusionCombo.add(ft.name());
        }
        hybridFusionCombo.select(0);
        hybridFusionCombo.setLayoutData(fillFieldData());
        return c;
    }

    private WeaviateQueryMode currentMode() {
        int idx = modeCombo.getSelectionIndex();
        if (idx < 0) idx = 0;
        return WeaviateQueryMode.values()[idx];
    }

    private void updateFieldVisibility() {
        Composite top;
        switch (currentMode()) {
            case BM25:
                refreshBm25PropertyList();
                top = bm25Composite;
                break;
            case NEAR_TEXT:
                top = nearTextComposite;
                break;
            case NEAR_VECTOR:
                top = nearVectorComposite;
                break;
            case HYBRID:
                top = hybridComposite;
                break;
            case FETCH:
            default:
                top = emptyComposite;
        }
        fieldsLayout.topControl = top;
        fieldsHolder.layout();
    }

    private void refreshBm25PropertyList() {
        if (bm25PropertiesList == null || bm25PropertiesList.isDisposed()) return;
        List<String> selectedBefore = List.of(bm25PropertiesList.getSelection());
        bm25PropertiesList.removeAll();
        for (String name : currentPropertyNames()) {
            bm25PropertiesList.add(name);
        }
        if (!selectedBefore.isEmpty()) {
            List<Integer> indices = new ArrayList<>();
            String[] items = bm25PropertiesList.getItems();
            for (int i = 0; i < items.length; i++) {
                if (selectedBefore.contains(items[i])) {
                    indices.add(i);
                }
            }
            int[] idxArr = new int[indices.size()];
            for (int i = 0; i < indices.size(); i++) idxArr[i] = indices.get(i);
            bm25PropertiesList.select(idxArr);
        }
    }

    private List<String> currentPropertyNames() {
        WeaviateCollection collection = currentCollection();
        if (collection == null) return Collections.emptyList();
        try {
            List<WeaviateProperty> attrs = collection.getAttributes(new VoidProgressMonitor());
            List<String> out = new ArrayList<>(attrs.size());
            for (WeaviateProperty p : attrs) out.add(p.getName());
            return out;
        } catch (DBException e) {
            log.debug("Failed to load Weaviate properties for BM25 picker", e);
            return Collections.emptyList();
        }
    }

    private void createFilterSection(Composite parent) {
        filtersGroup = new Group(parent, SWT.NONE);
        filtersGroup.setText("Filters");
        GridLayout gl = new GridLayout(1, false);
        gl.marginWidth = 6;
        gl.marginHeight = 6;
        filtersGroup.setLayout(gl);
        filtersGroup.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite header = new Composite(filtersGroup, SWT.NONE);
        GridLayout hl = new GridLayout(4, false);
        hl.marginWidth = 0;
        hl.marginHeight = 0;
        header.setLayout(hl);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(header, SWT.NONE).setText("Match:");
        filterAndRadio = new Button(header, SWT.RADIO);
        filterAndRadio.setText("All (AND)");
        filterAndRadio.setSelection(true);
        filterOrRadio = new Button(header, SWT.RADIO);
        filterOrRadio.setText("Any (OR)");

        Button addRow = new Button(header, SWT.PUSH);
        addRow.setText("+ Add filter");
        addRow.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        addRow.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                addFilterRow(null);
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

    private void addFilterRow(@Nullable WeaviateFilterRow seed) {
        if (filterRowsHolder == null || filterRowsHolder.isDisposed()) return;
        FilterRowUi ui = new FilterRowUi(filterRowsHolder, currentPropertyNames(), seed);
        filterRowUis.add(ui);
        filterRowsHolder.layout(true, true);
        filtersGroup.layout(true, true);
    }

    private void clearFilterRows() {
        for (FilterRowUi ui : new ArrayList<>(filterRowUis)) {
            ui.dispose();
        }
        filterRowUis.clear();
    }

    private List<WeaviateFilterRow> collectFilterRows() {
        List<WeaviateFilterRow> rows = new ArrayList<>(filterRowUis.size());
        for (FilterRowUi ui : filterRowUis) {
            WeaviateFilterRow row = ui.toRow();
            if (row != null) rows.add(row);
        }
        return rows;
    }

    private DBPDataKind dataKindForProperty(@NotNull String propertyName) {
        WeaviateCollection collection = currentCollection();
        if (collection == null) return DBPDataKind.STRING;
        try {
            for (WeaviateProperty p : collection.getAttributes(new VoidProgressMonitor())) {
                if (p.getName().equals(propertyName)) return p.getDataKind();
            }
        } catch (DBException ignored) {
            // fall through
        }
        return DBPDataKind.STRING;
    }

    private void loadSpecIntoUi() {
        WeaviateCollection collection = currentCollection();
        if (collection == null) return;
        WeaviateQuerySpec spec = collection.getQuerySpec();
        modeCombo.select(spec.getMode().ordinal());

        // Filter rows
        if (filterRowsHolder != null && !filterRowsHolder.isDisposed()) {
            clearFilterRows();
            for (WeaviateFilterRow row : spec.getFilterRows()) {
                addFilterRow(row);
            }
            if (filterAndRadio != null) {
                filterAndRadio.setSelection(!spec.isAnyFilter());
                filterOrRadio.setSelection(spec.isAnyFilter());
            }
        }

        switch (spec.getMode()) {
            case BM25:
                if (spec.getQuery() != null) bm25QueryField.setText(spec.getQuery());
                refreshBm25PropertyList();
                if (!spec.getQueryProperties().isEmpty()) {
                    List<String> selected = spec.getQueryProperties();
                    String[] items = bm25PropertiesList.getItems();
                    List<Integer> indices = new ArrayList<>();
                    for (int i = 0; i < items.length; i++) {
                        if (selected.contains(items[i])) indices.add(i);
                    }
                    int[] idxArr = new int[indices.size()];
                    for (int i = 0; i < indices.size(); i++) idxArr[i] = indices.get(i);
                    bm25PropertiesList.select(idxArr);
                }
                break;
            case NEAR_TEXT:
                if (spec.getQuery() != null) nearTextQueryField.setText(spec.getQuery());
                if (spec.getDistance() != null) nearTextDistanceField.setText(spec.getDistance().toString());
                break;
            case NEAR_VECTOR:
                if (spec.getVector() != null) nearVectorField.setText(WeaviateVectorParser.format(spec.getVector()));
                if (spec.getDistance() != null) nearVectorDistanceField.setText(spec.getDistance().toString());
                break;
            case HYBRID:
                if (spec.getQuery() != null) hybridQueryField.setText(spec.getQuery());
                if (spec.getAlpha() != null) hybridAlphaSpinner.setSelection(Math.round(spec.getAlpha() * 100));
                if (spec.getFusionType() != null) {
                    hybridFusionCombo.setText(spec.getFusionType().name());
                } else {
                    hybridFusionCombo.select(0);
                }
                break;
            default:
                break;
        }
    }

    private void runQuery() {
        WeaviateCollection collection = currentCollection();
        if (collection == null) {
            showError("Active result set is not a Weaviate collection.");
            return;
        }
        WeaviateQuerySpec spec;
        try {
            spec = buildSpec();
        } catch (IllegalArgumentException e) {
            showError(e.getMessage());
            return;
        }
        collection.setQuerySpec(spec);
        collection.clearLastQueryError();
        showInfo("Running…");
        presentation.getController().refreshData(() -> Display.getDefault().asyncExec(this::refreshStatusFromCollection));
    }

    private void resetToFetch() {
        WeaviateCollection collection = currentCollection();
        if (collection == null) {
            showError("Active result set is not a Weaviate collection.");
            return;
        }
        collection.setQuerySpec(WeaviateQuerySpec.fetch());
        collection.clearLastQueryError();
        modeCombo.select(WeaviateQueryMode.FETCH.ordinal());
        updateFieldVisibility();
        showInfo("Running…");
        presentation.getController().refreshData(() -> Display.getDefault().asyncExec(this::refreshStatusFromCollection));
    }

    private WeaviateQuerySpec buildSpec() {
        WeaviateQueryMode mode = currentMode();
        List<WeaviateFilterRow> rows = collectFilterRows();
        boolean any = filterOrRadio != null && filterOrRadio.getSelection();
        WeaviateQuerySpec.Builder builder;
        switch (mode) {
            case BM25: {
                String q = requireNonBlank(bm25QueryField.getText(), "BM25 query is required.");
                List<String> props = List.of(bm25PropertiesList.getSelection());
                builder = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
                    .query(q)
                    .queryProperties(props);
                break;
            }
            case NEAR_TEXT: {
                String q = requireNonBlank(nearTextQueryField.getText(), "Near Text query is required.");
                Float distance = parseOptionalFloat(nearTextDistanceField.getText(), "distance");
                builder = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_TEXT)
                    .query(q)
                    .distance(distance);
                break;
            }
            case NEAR_VECTOR: {
                String raw = nearVectorField.getText();
                float[] vec;
                try {
                    vec = WeaviateVectorParser.parse(raw);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid vector: " + e.getMessage());
                }
                if (vec == null || vec.length == 0) {
                    throw new IllegalArgumentException("Near Vector requires a non-empty vector.");
                }
                Float distance = parseOptionalFloat(nearVectorDistanceField.getText(), "distance");
                builder = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_VECTOR)
                    .vector(vec)
                    .distance(distance);
                break;
            }
            case HYBRID: {
                String q = requireNonBlank(hybridQueryField.getText(), "Hybrid query is required.");
                float alpha = hybridAlphaSpinner.getSelection() / 100.0f;
                WeaviateHybridFusion fusionType = readFusionType();
                builder = WeaviateQuerySpec.builder(WeaviateQueryMode.HYBRID)
                    .query(q)
                    .alpha(alpha)
                    .fusionType(fusionType);
                break;
            }
            case FETCH:
            default:
                builder = WeaviateQuerySpec.builder(WeaviateQueryMode.FETCH);
        }
        return builder.filterRows(rows).anyFilter(any).build();
    }

    private WeaviateHybridFusion readFusionType() {
        if (hybridFusionCombo == null) return null;
        int idx = hybridFusionCombo.getSelectionIndex();
        if (idx <= 0) return null;
        String name = hybridFusionCombo.getItem(idx);
        try {
            return WeaviateHybridFusion.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static Float parseOptionalFloat(String raw, String label) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) return null;
        try {
            return Float.parseFloat(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + " value: " + trimmed);
        }
    }

    private static String requireNonBlank(String value, String msg) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(msg);
        }
        return value.strip();
    }

    private WeaviateCollection currentCollection() {
        if (presentation == null || presentation.getController() == null) return null;
        Object source = presentation.getController().getModel().getSingleSource();
        if (source instanceof WeaviateCollection wc) return wc;
        return null;
    }

    private Composite createBanner(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        GridLayout l = new GridLayout(2, false);
        l.marginWidth = 8;
        l.marginHeight = 6;
        c.setLayout(l);
        GridData gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gd.exclude = true;
        c.setLayoutData(gd);

        Display display = parent.getDisplay();
        errorBg = new Color(display, 255, 224, 224);
        infoBg = new Color(display, 224, 240, 255);

        bannerLabel = new Label(c, SWT.WRAP);
        bannerLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        FontData fd = bannerLabel.getFont().getFontData()[0];
        bannerFont = new Font(display, fd.getName(), Math.max(fd.getHeight(), 11), SWT.BOLD);
        bannerLabel.setFont(bannerFont);

        Button dismiss = new Button(c, SWT.PUSH | SWT.FLAT);
        dismiss.setText("✕");
        dismiss.setToolTipText("Dismiss");
        dismiss.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        dismiss.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                dismissBanner();
            }
        });

        c.setVisible(false);
        c.addDisposeListener(new DisposeListener() {
            @Override
            public void widgetDisposed(DisposeEvent e) {
                if (errorBg != null && !errorBg.isDisposed()) errorBg.dispose();
                if (infoBg != null && !infoBg.isDisposed()) infoBg.dispose();
                if (bannerFont != null && !bannerFont.isDisposed()) bannerFont.dispose();
            }
        });
        return c;
    }

    private void dismissBanner() {
        WeaviateCollection collection = currentCollection();
        if (collection != null) {
            collection.clearLastQueryError();
        }
        hideBanner();
    }

    private void showError(String msg) {
        showBanner("⚠ " + msg, true);
    }

    private void showInfo(String msg) {
        showBanner(msg, false);
    }

    private void hideBanner() {
        if (banner == null || banner.isDisposed()) return;
        ((GridData) banner.getLayoutData()).exclude = true;
        banner.setVisible(false);
        if (bannerLabel != null && !bannerLabel.isDisposed()) {
            bannerLabel.setText("");
            bannerLabel.setToolTipText("");
        }
        banner.getParent().layout(true, true);
    }

    private void showBanner(String msg, boolean error) {
        if (banner == null || banner.isDisposed()) return;
        Color bg = error ? errorBg : infoBg;
        Color fg = error
            ? Display.getCurrent().getSystemColor(SWT.COLOR_DARK_RED)
            : Display.getCurrent().getSystemColor(SWT.COLOR_DARK_BLUE);
        banner.setBackground(bg);
        bannerLabel.setBackground(bg);
        bannerLabel.setForeground(fg);
        bannerLabel.setText(msg);
        bannerLabel.setToolTipText(msg);
        ((GridData) banner.getLayoutData()).exclude = false;
        banner.setVisible(true);
        banner.getParent().layout(true, true);
    }

    private void refreshStatusFromCollection() {
        WeaviateCollection collection = currentCollection();
        if (collection == null) {
            hideBanner();
            return;
        }
        String err = collection.getLastQueryError();
        if (err != null) {
            showError(err);
            // Also surface as a native DBeaver warning toast — visible even if the user
            // is looking elsewhere.
            try {
                DBWorkbench.getPlatformUI().showWarningNotification(
                    "Weaviate query failed",
                    "Collection \"" + collection.getName() + "\": " + err
                );
            } catch (Throwable t) {
                log.debug("Failed to surface DBeaver notification", t);
            }
        } else {
            hideBanner();
        }
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public void activatePanel() {
        loadSpecIntoUi();
        updateFieldVisibility();
        refreshStatusFromCollection();
    }

    @Override
    public void deactivatePanel() {
    }

    @Override
    public void setFocus() {
        if (modeCombo != null && !modeCombo.isDisposed()) {
            modeCombo.setFocus();
        }
    }

    @Override
    public void refresh(boolean force) {
        loadSpecIntoUi();
        updateFieldVisibility();
        refreshStatusFromCollection();
    }

    @Override
    public void contributeActions(IContributionManager manager) {
        // No toolbar actions for now.
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

        FilterRowUi(@NotNull Composite parent, @NotNull List<String> propertyNames, @Nullable WeaviateFilterRow seed) {
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
            for (DBCLogicalOperator op : WeaviateFilterRow.SUPPORTED_OPERATORS) {
                opCombo.add(operatorLabel(op));
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
            remove.setToolTipText("Remove filter");
            remove.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    dispose();
                    filterRowUis.remove(FilterRowUi.this);
                    filterRowsHolder.layout(true, true);
                    filtersGroup.layout(true, true);
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
            valueField.setEnabled(WeaviateFilterRow.takesValue(currentOperator()));
        }

        private DBCLogicalOperator currentOperator() {
            int idx = opCombo.getSelectionIndex();
            if (idx < 0) idx = 0;
            return WeaviateFilterRow.SUPPORTED_OPERATORS.get(idx);
        }

        @Nullable
        WeaviateFilterRow toRow() {
            String property = propertyCombo.getText();
            if (property == null || property.isBlank()) return null;
            DBCLogicalOperator op = currentOperator();
            String raw = valueField.getText();
            DBPDataKind kind = dataKindForProperty(property);
            return new WeaviateFilterRow(property, op, raw, kind);
        }

        void dispose() {
            if (!container.isDisposed()) container.dispose();
        }
    }

    @NotNull
    private static String operatorLabel(@NotNull DBCLogicalOperator op) {
        switch (op) {
            case EQUALS: return "=";
            case NOT_EQUALS: return "≠";
            case GREATER: return ">";
            case GREATER_EQUALS: return "≥";
            case LESS: return "<";
            case LESS_EQUALS: return "≤";
            case LIKE: return "LIKE";
            case IS_NULL: return "IS NULL";
            case IS_NOT_NULL: return "IS NOT NULL";
            case IN: return "IN";
            default: return op.name();
        }
    }
}
