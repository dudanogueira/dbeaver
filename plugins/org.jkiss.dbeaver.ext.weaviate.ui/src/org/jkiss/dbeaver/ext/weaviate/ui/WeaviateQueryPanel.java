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
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionListener;
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
import org.eclipse.swt.widgets.Scale;
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
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.exec.DBCLogicalOperator;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetPresentation;
import org.jkiss.dbeaver.ui.controls.resultset.panel.ResultSetPanelBase;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class WeaviateQueryPanel extends ResultSetPanelBase {

    public static final String PANEL_ID = "weaviate-query";

    private static final Log log = Log.getLog(WeaviateQueryPanel.class);
    private static final String NO_FUSION = "(default)";

    private IResultSetPresentation presentation;

    private Combo modeCombo;
    /** Mode the fields currently show, so switching can carry the query text across. */
    private WeaviateQueryMode displayedMode;
    /** One autocut control per mode that supports it; a plain fetch has none. */
    private final Map<WeaviateQueryMode, Spinner> autoCutSpinners = new EnumMap<>(WeaviateQueryMode.class);
    /** One explain-score toggle per mode that can produce an explanation. */
    private final Map<WeaviateQueryMode, Button> explainScoreChecks = new EnumMap<>(WeaviateQueryMode.class);
    private Composite tenantRow;
    private Label tenantLabel;
    private Combo tenantCombo;
    /** One include-vectors toggle per mode, beside that mode's other result options. */
    private final Map<WeaviateQueryMode, Button> includeVectorChecks = new EnumMap<>(WeaviateQueryMode.class);
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
    private Composite nearObjectComposite;
    private Text nearObjectField;
    private Text nearObjectDistanceField;
    private Text nearVectorDistanceField;
    private Composite hybridComposite;
    private Text hybridQueryField;
    private Scale hybridAlphaScale;
    private Label hybridAlphaValue;
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

        tenantRow = new Composite(root, SWT.NONE);
        GridLayout tenantLayout = new GridLayout(2, false);
        tenantLayout.marginWidth = 0;
        tenantLayout.marginHeight = 0;
        tenantRow.setLayout(tenantLayout);
        tenantRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tenantLabel = new Label(tenantRow, SWT.NONE);
        tenantLabel.setText(WeaviateUIMessages.query_tenant);
        tenantCombo = new Combo(tenantRow, SWT.READ_ONLY);
        tenantCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tenantCombo.setToolTipText(WeaviateUIMessages.query_tenant_tip);
        tenantCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                dismissBanner();
                WeaviateCollection collection = currentCollection();
                if (collection != null) {
                    collection.setQuerySpec(collection.getQuerySpec().withTenant(currentTenant()));
                }
                // Switching tenant changes the whole result set, so reload rather than making
                // the user press Run to see a different tenant's data.
                runQuery();
            }
        });
        setTenantRowVisible(false);

        new Label(topRow, SWT.NONE).setText(WeaviateUIMessages.query_mode);
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
        resetButton.setText(WeaviateUIMessages.query_reset);
        resetButton.setToolTipText(WeaviateUIMessages.query_reset_tip);
        resetButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                resetToFetch();
            }
        });

        banner = createBanner(root);

        fieldsHolder = new Composite(root, SWT.NONE);
        // No fixed height. StackLayout#computeSize already reports the tallest child, so the
        // holder sizes to whichever mode needs the most room and every mode gets the same
        // height -- no jumping as you switch. A fixed hint clipped the taller panels instead:
        // Hybrid needs four rows and ran underneath the filter section below it.
        fieldsHolder.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        fieldsLayout = new StackLayout();
        fieldsHolder.setLayout(fieldsLayout);

        emptyComposite = createEmptyFields(fieldsHolder);
        bm25Composite = createBm25Fields(fieldsHolder);
        nearTextComposite = createNearTextFields(fieldsHolder);
        nearVectorComposite = createNearVectorFields(fieldsHolder);
        nearObjectComposite = createNearObjectFields(fieldsHolder);
        hybridComposite = createHybridFields(fieldsHolder);

        createFilterSection(root);

        // Must run here as well as in activatePanel(): the row starts hidden, and on first
        // display of the panel activatePanel() has not necessarily fired yet, so without this
        // the tenant picker never appears for a multi-tenant collection.
        refreshTenants();
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
        l.setText(WeaviateUIMessages.query_fetch_hint);
        addIncludeVectorField(c, WeaviateQueryMode.FETCH);
        l.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return c;
    }

    private Composite createBm25Fields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_query);
        bm25QueryField = new Text(c, SWT.BORDER);
        bm25QueryField.setLayoutData(fillFieldData());
        bm25QueryField.setMessage(WeaviateUIMessages.query_search_hint);
        runOnEnter(bm25QueryField);

        Label lbl = new Label(c, SWT.NONE);
        lbl.setText(WeaviateUIMessages.query_properties);
        lbl.setLayoutData(labelTopData());
        bm25PropertiesList = new org.eclipse.swt.widgets.List(c, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL);
        GridData lgd = new GridData(SWT.FILL, SWT.FILL, true, true);
        lgd.heightHint = 80;
        bm25PropertiesList.setLayoutData(lgd);
        bm25PropertiesList.setToolTipText(WeaviateUIMessages.query_bm25_properties_tip);
        addIncludeVectorField(c, WeaviateQueryMode.BM25);
        addExplainScoreField(c, WeaviateQueryMode.BM25);
        addAutoCutField(c, WeaviateQueryMode.BM25);
        return c;
    }

    private Composite createNearTextFields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_query);
        nearTextQueryField = new Text(c, SWT.BORDER);
        nearTextQueryField.setLayoutData(fillFieldData());
        nearTextQueryField.setMessage(WeaviateUIMessages.query_search_hint);
        runOnEnter(nearTextQueryField);

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_distance);
        nearTextDistanceField = new Text(c, SWT.BORDER);
        nearTextDistanceField.setLayoutData(fillFieldData());
        nearTextDistanceField.setMessage(WeaviateUIMessages.query_distance_hint);
        runOnEnter(nearTextDistanceField);
        addIncludeVectorField(c, WeaviateQueryMode.NEAR_TEXT);
        addAutoCutField(c, WeaviateQueryMode.NEAR_TEXT);
        return c;
    }

    private Composite createNearVectorFields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        Label vl = new Label(c, SWT.NONE);
        vl.setText(WeaviateUIMessages.query_vector);
        vl.setLayoutData(labelTopData());
        nearVectorField = new Text(c, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP);
        GridData vgd = new GridData(SWT.FILL, SWT.FILL, true, true);
        vgd.heightHint = 80;
        nearVectorField.setLayoutData(vgd);
        nearVectorField.setMessage("[0.1, 0.2, 0.3, ...] (comma-separated, brackets optional)");

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_distance);
        nearVectorDistanceField = new Text(c, SWT.BORDER);
        nearVectorDistanceField.setLayoutData(fillFieldData());
        nearVectorDistanceField.setMessage(WeaviateUIMessages.query_distance_hint);
        runOnEnter(nearVectorDistanceField);
        addIncludeVectorField(c, WeaviateQueryMode.NEAR_VECTOR);
        addAutoCutField(c, WeaviateQueryMode.NEAR_VECTOR);
        return c;
    }

    private Composite createNearObjectFields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_object_id);
        nearObjectField = new Text(c, SWT.BORDER);
        nearObjectField.setLayoutData(fillFieldData());
        nearObjectField.setMessage(WeaviateUIMessages.query_object_id_hint);
        nearObjectField.setToolTipText(WeaviateUIMessages.query_object_id_tip);
        runOnEnter(nearObjectField);

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_distance);
        nearObjectDistanceField = new Text(c, SWT.BORDER);
        nearObjectDistanceField.setLayoutData(fillFieldData());
        nearObjectDistanceField.setMessage(WeaviateUIMessages.query_distance_hint);
        runOnEnter(nearObjectDistanceField);
        addIncludeVectorField(c, WeaviateQueryMode.NEAR_OBJECT);
        addAutoCutField(c, WeaviateQueryMode.NEAR_OBJECT);
        return c;
    }

    private Composite createHybridFields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_query);
        hybridQueryField = new Text(c, SWT.BORDER);
        hybridQueryField.setLayoutData(fillFieldData());
        hybridQueryField.setMessage(WeaviateUIMessages.query_search_hint);
        runOnEnter(hybridQueryField);

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_alpha);
        // A slider rather than a number box: alpha is a blend between two named extremes, and
        // the useful question is "more keyword or more vector", not "which hundredth".
        Composite alphaRow = new Composite(c, SWT.NONE);
        GridLayout alphaLayout = new GridLayout(4, false);
        alphaLayout.marginWidth = 0;
        alphaLayout.marginHeight = 0;
        alphaRow.setLayout(alphaLayout);
        alphaRow.setLayoutData(fillFieldData());

        Label keywordEnd = new Label(alphaRow, SWT.NONE);
        keywordEnd.setText(WeaviateUIMessages.query_alpha_keyword_end);

        hybridAlphaScale = new Scale(alphaRow, SWT.HORIZONTAL);
        hybridAlphaScale.setMinimum(0);
        hybridAlphaScale.setMaximum(100);
        hybridAlphaScale.setIncrement(5);
        hybridAlphaScale.setPageIncrement(25);
        hybridAlphaScale.setSelection((int) (WeaviateQuerySpec.DEFAULT_HYBRID_ALPHA * 100));
        GridData scaleGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        scaleGd.widthHint = 200;
        hybridAlphaScale.setLayoutData(scaleGd);
        hybridAlphaScale.setToolTipText(WeaviateUIMessages.query_alpha_tip);

        Label vectorEnd = new Label(alphaRow, SWT.NONE);
        vectorEnd.setText(WeaviateUIMessages.query_alpha_vector_end);

        hybridAlphaValue = new Label(alphaRow, SWT.NONE);
        GridData valueGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        // Fixed width so the row does not jitter as the number changes while dragging.
        valueGd.widthHint = UIUtils.getFontHeight(hybridAlphaValue) * 6;
        hybridAlphaValue.setLayoutData(valueGd);
        hybridAlphaScale.addSelectionListener(SelectionListener.widgetSelectedAdapter(
            e -> updateAlphaLabel()));
        updateAlphaLabel();

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_fusion);
        hybridFusionCombo = new Combo(c, SWT.READ_ONLY);
        hybridFusionCombo.add(NO_FUSION);
        for (WeaviateHybridFusion ft : WeaviateHybridFusion.values()) {
            hybridFusionCombo.add(ft.name());
        }
        hybridFusionCombo.select(0);
        hybridFusionCombo.setLayoutData(fillFieldData());
        addIncludeVectorField(c, WeaviateQueryMode.HYBRID);
        addExplainScoreField(c, WeaviateQueryMode.HYBRID);
        addAutoCutField(c, WeaviateQueryMode.HYBRID);
        return c;
    }

    /**
     * Canonical 8-4-4-4-12 only. {@link java.util.UUID#fromString} also accepts short forms like
     * "1-2-3-4-5", which the server then rejects with a less helpful message.
     */
    private static boolean isUuid(String value) {
        String token = value.trim();
        if (token.length() != 36) {
            return false;
        }
        try {
            return java.util.UUID.fromString(token).toString().equalsIgnoreCase(token);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void setTenantRowVisible(boolean visible) {
        if (tenantRow == null || tenantRow.isDisposed()) {
            return;
        }
        tenantRow.setVisible(visible);
        ((GridData) tenantRow.getLayoutData()).exclude = !visible;
        tenantRow.getParent().layout(true, true);
    }

    /**
     * Show the tenant picker only for multi-tenant collections, and preselect nothing so the
     * choice is deliberate -- a query against the wrong tenant returns plausible but wrong rows.
     */
    private void refreshTenants() {
        WeaviateCollection collection = currentCollection();
        if (collection == null) {
            log.debug("Tenant picker hidden: no Weaviate collection resolved for this result set");
            setTenantRowVisible(false);
            return;
        }
        if (!collection.isMultiTenant()) {
            log.debug("Tenant picker hidden: " + collection.getName() + " is not multi-tenant");
            setTenantRowVisible(false);
            return;
        }
        log.debug("Tenant picker shown for multi-tenant collection " + collection.getName());
        setTenantRowVisible(true);
        // Follow the spec, not whatever the combo happened to show: the tenant is usually set
        // from the read-path dialog or the "Select Tenant..." command, and the combo has no way
        // to know about either.
        String current = collection.getQuerySpec().getTenant();
        tenantCombo.removeAll();
        try {
            for (String name : collection.listTenantNames(new VoidProgressMonitor())) {
                tenantCombo.add(name);
            }
        } catch (DBException e) {
            log.debug("Cannot list tenants", e);
            showError(e.getMessage());
            return;
        }
        if (tenantCombo.getItemCount() == 0) {
            showError(WeaviateUIMessages.query_tenant_none);
            return;
        }
        int idx = current == null ? -1 : tenantCombo.indexOf(current);
        if (idx >= 0) {
            tenantCombo.select(idx);
        } else {
            // Nothing chosen yet, or the stored tenant no longer exists -- leave it unselected
            // rather than silently pointing at a different tenant's data.
            tenantCombo.deselectAll();
        }
    }

    /**
     * Point the combo at the tenant the spec actually holds, without refetching the list.
     */
    private void syncTenantSelection() {
        WeaviateCollection collection = currentCollection();
        if (collection == null || tenantCombo == null || tenantCombo.isDisposed()) {
            return;
        }
        String current = collection.getQuerySpec().getTenant();
        if (current == null) {
            return;
        }
        int idx = tenantCombo.indexOf(current);
        if (idx >= 0 && idx != tenantCombo.getSelectionIndex()) {
            tenantCombo.select(idx);
        }
    }

    @Nullable
    private String currentTenant() {
        if (tenantCombo == null || tenantCombo.isDisposed() || !tenantRow.isVisible()) {
            return null;
        }
        int idx = tenantCombo.getSelectionIndex();
        return idx < 0 ? null : tenantCombo.getItem(idx);
    }

    private void updateAlphaLabel() {
        if (hybridAlphaValue == null || hybridAlphaValue.isDisposed()) {
            return;
        }
        hybridAlphaValue.setText(String.format("%.2f", hybridAlphaScale.getSelection() / 100.0f));
        hybridAlphaValue.getParent().layout();
    }

    /**
     * Adds an autocut control to a mode's own field panel.
     * <p>
     * Per mode rather than shared: autocut is a property of a ranked query, and a plain fetch
     * has no ranking to cut on -- so rather than showing a disabled control there, the option
     * simply does not exist for it.
     */
    private void addAutoCutField(@NotNull Composite c, @NotNull WeaviateQueryMode mode) {
        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_autocut);
        Spinner spinner = new Spinner(c, SWT.BORDER);
        spinner.setMinimum(0);
        spinner.setMaximum(100);
        spinner.setIncrement(1);
        spinner.setSelection(0);
        spinner.setToolTipText(WeaviateUIMessages.query_autocut_tip);
        autoCutSpinners.put(mode, spinner);
    }

    /**
     * Adds the explain-score toggle to a mode's own field panel.
     * <p>
     * Off by default and only for modes that can explain a score: the explanation is a long
     * string of per-term arithmetic, useful when tuning relevance and noise the rest of the
     * time, so it is asked for rather than assumed.
     */
    private void addExplainScoreField(@NotNull Composite c, @NotNull WeaviateQueryMode mode) {
        new Label(c, SWT.NONE).setText("");
        Button check = new Button(c, SWT.CHECK);
        check.setText(WeaviateUIMessages.query_explain_score);
        check.setToolTipText(WeaviateUIMessages.query_explain_score_tip);
        check.setSelection(false);
        explainScoreChecks.put(mode, check);
    }

    /**
     * Adds the include-vectors toggle to a mode's own field panel, beside the other options that
     * decide what comes back. Unlike explain-score this applies to every mode, a plain fetch
     * included.
     */
    private void addIncludeVectorField(@NotNull Composite c, @NotNull WeaviateQueryMode mode) {
        new Label(c, SWT.NONE).setText("");
        Button check = new Button(c, SWT.CHECK);
        check.setText(WeaviateUIMessages.query_include_vector);
        check.setToolTipText(WeaviateUIMessages.query_include_vector_tip);
        includeVectorChecks.put(mode, check);
    }

    private boolean currentIncludeVector() {
        Button check = includeVectorChecks.get(currentMode());
        return check != null && !check.isDisposed() && check.getSelection();
    }

    private boolean currentExplainScore() {
        Button check = explainScoreChecks.get(currentMode());
        return check != null && !check.isDisposed() && check.getSelection();
    }

    @Nullable
    private Integer currentAutoCut() {
        Spinner spinner = autoCutSpinners.get(currentMode());
        if (spinner == null || spinner.isDisposed() || spinner.getSelection() <= 0) {
            return null;
        }
        return spinner.getSelection();
    }

    /**
     * Run the query when Enter is pressed in a field.
     * <p>
     * Single-line inputs only: SWT fires DefaultSelection on Enter for those, while in a
     * multi-line field -- the near-vector box -- Enter is how you add a line, so hijacking it
     * would stop you typing a vector across lines.
     */
    private void runOnEnter(@NotNull Text field) {
        field.addListener(SWT.DefaultSelection, e -> runQuery());
    }

    private WeaviateQueryMode currentMode() {
        int idx = modeCombo.getSelectionIndex();
        if (idx < 0) idx = 0;
        return WeaviateQueryMode.values()[idx];
    }

    /**
     * The text-search input for a mode, or null if it does not take one.
     * <p>
     * Near Vector and Near Object are excluded on purpose: a vector and an object id are not
     * search text, and carrying a sentence into either would only produce an invalid query.
     */
    @Nullable
    private Text queryFieldFor(@Nullable WeaviateQueryMode mode) {
        if (mode == null) {
            return null;
        }
        return switch (mode) {
            case BM25 -> bm25QueryField;
            case NEAR_TEXT -> nearTextQueryField;
            case HYBRID -> hybridQueryField;
            default -> null;
        };
    }

    /**
     * Carry the query text from the mode being left to the one being entered, so trying the same
     * search a different way does not mean retyping it.
     */
    private void carryQueryText(@NotNull WeaviateQueryMode from, @NotNull WeaviateQueryMode to) {
        Text source = queryFieldFor(from);
        Text target = queryFieldFor(to);
        if (source == null || target == null || source.isDisposed() || target.isDisposed()) {
            return;
        }
        String text = source.getText();
        if (!text.equals(target.getText())) {
            target.setText(text);
        }
    }

    private void updateFieldVisibility() {
        WeaviateQueryMode mode = currentMode();
        if (displayedMode != null && displayedMode != mode) {
            carryQueryText(displayedMode, mode);
        }
        displayedMode = mode;

        Composite top;
        switch (mode) {
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
            case NEAR_OBJECT:
                top = nearObjectComposite;
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
        // The holder's preferred height can change when panels are rebuilt, so re-flow the
        // whole panel rather than just the holder's children.
        if (fieldsHolder.getParent() != null && !fieldsHolder.getParent().isDisposed()) {
            fieldsHolder.getParent().layout(true, true);
        }
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
            List<WeaviateProperty> attrs = collection.getProperties(new VoidProgressMonitor());
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
        filtersGroup.setText(WeaviateUIMessages.query_filters);
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
            for (WeaviateProperty p : collection.getProperties(new VoidProgressMonitor())) {
                if (p.getName().equals(propertyName)) return p.getDataKind();
            }
        } catch (DBException ignored) {
            // fall through
        }
        return DBPDataKind.STRING;
    }

    private void loadAutoCutIntoUi(@NotNull WeaviateQuerySpec spec) {
        Spinner spinner = autoCutSpinners.get(spec.getMode());
        if (spinner != null && !spinner.isDisposed()) {
            spinner.setSelection(spec.getAutoCut() == null ? 0 : spec.getAutoCut());
        }
        Button check = explainScoreChecks.get(spec.getMode());
        if (check != null && !check.isDisposed()) {
            check.setSelection(spec.isExplainScore());
        }
        Button vectors = includeVectorChecks.get(spec.getMode());
        if (vectors != null && !vectors.isDisposed()) {
            vectors.setSelection(spec.isIncludeVector());
        }
    }

    private void loadSpecIntoUi() {
        WeaviateCollection collection = currentCollection();
        if (collection == null) return;
        WeaviateQuerySpec spec = collection.getQuerySpec();
        modeCombo.select(spec.getMode().ordinal());
        // Adopt the spec's mode first: updateFieldVisibility must not treat this as a switch and
        // copy text over the values just loaded.
        displayedMode = spec.getMode();
        loadAutoCutIntoUi(spec);


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
            case NEAR_OBJECT:
                if (spec.getObjectId() != null) nearObjectField.setText(spec.getObjectId());
                if (spec.getDistance() != null) nearObjectDistanceField.setText(spec.getDistance().toString());
                break;
            case HYBRID:
                if (spec.getQuery() != null) hybridQueryField.setText(spec.getQuery());
                if (spec.getAlpha() != null) {
                    hybridAlphaScale.setSelection(Math.round(spec.getAlpha() * 100));
                    updateAlphaLabel();
                }
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
            showError(WeaviateUIMessages.query_not_weaviate);
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
        showInfo(WeaviateUIMessages.query_running);
        presentation.getController().refreshData(() -> Display.getDefault().asyncExec(this::refreshStatusFromCollection));
    }

    private void resetToFetch() {
        WeaviateCollection collection = currentCollection();
        if (collection == null) {
            showError(WeaviateUIMessages.query_not_weaviate);
            return;
        }
        collection.setQuerySpec(WeaviateQuerySpec.fetch());
        collection.clearLastQueryError();
        modeCombo.select(WeaviateQueryMode.FETCH.ordinal());
        updateFieldVisibility();
        showInfo(WeaviateUIMessages.query_running);
        presentation.getController().refreshData(() -> Display.getDefault().asyncExec(this::refreshStatusFromCollection));
    }

    private WeaviateQuerySpec buildSpec() {
        WeaviateQueryMode mode = currentMode();
        List<WeaviateFilterRow> rows = collectFilterRows();
        boolean any = filterOrRadio != null && filterOrRadio.getSelection();
        WeaviateQuerySpec.Builder builder;
        switch (mode) {
            case BM25: {
                String q = requireNonBlank(bm25QueryField.getText(), WeaviateUIMessages.query_required_bm25);
                List<String> props = List.of(bm25PropertiesList.getSelection());
                builder = WeaviateQuerySpec.builder(WeaviateQueryMode.BM25)
                    .query(q)
                    .queryProperties(props);
                break;
            }
            case NEAR_TEXT: {
                String q = requireNonBlank(nearTextQueryField.getText(), WeaviateUIMessages.query_required_near_text);
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
                    throw new IllegalArgumentException(NLS.bind(WeaviateUIMessages.query_invalid_vector, e.getMessage()));
                }
                if (vec == null || vec.length == 0) {
                    throw new IllegalArgumentException(WeaviateUIMessages.query_required_vector);
                }
                Float distance = parseOptionalFloat(nearVectorDistanceField.getText(), "distance");
                builder = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_VECTOR)
                    .vector(vec)
                    .distance(distance);
                break;
            }
            case NEAR_OBJECT: {
                String uuid = requireNonBlank(
                    nearObjectField.getText(), WeaviateUIMessages.query_required_object_id);
                if (!isUuid(uuid)) {
                    throw new IllegalArgumentException(
                        NLS.bind(WeaviateUIMessages.query_invalid_object_id, uuid));
                }
                Float distance = parseOptionalFloat(nearObjectDistanceField.getText(), "distance");
                builder = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_OBJECT)
                    .objectId(uuid)
                    .distance(distance);
                break;
            }
            case HYBRID: {
                String q = requireNonBlank(hybridQueryField.getText(), WeaviateUIMessages.query_required_hybrid);
                float alpha = hybridAlphaScale.getSelection() / 100.0f;
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
        return builder.filterRows(rows).anyFilter(any)
            .tenant(currentTenant())
            .autoCut(currentAutoCut())
            .explainScore(currentExplainScore())
            .includeVector(currentIncludeVector())
            .build();
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
        dismiss.setToolTipText(WeaviateUIMessages.query_dismiss);
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
                    WeaviateUIMessages.query_failed,
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
        refreshTenants();
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
        refreshTenants();
        // refreshTenants ran before the read completed the first time round, so re-sync after.
        syncTenantSelection();
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
            remove.setToolTipText(WeaviateUIMessages.query_remove_filter);
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

    /**
     * Label for the operator dropdown.
     * <p>
     * Spelled out rather than using bare symbols: a lone "≠" is easy to misread as "=" at
     * combo size, and ILIKE/NOT_LIKE previously fell through to the raw enum name, so the
     * list mixed "LIKE" with "NOT_LIKE". Every entry now names what it does.
     */
    @NotNull
    private static String operatorLabel(@NotNull DBCLogicalOperator op) {
        switch (op) {
            case EQUALS: return "= (equals)";
            case NOT_EQUALS: return "!= (not equals)";
            case GREATER: return "> (greater)";
            case GREATER_EQUALS: return ">= (greater or equal)";
            case LESS: return "< (less)";
            case LESS_EQUALS: return "<= (less or equal)";
            case LIKE: return "LIKE";
            case ILIKE: return "ILIKE (case-insensitive)";
            case NOT_LIKE: return "NOT LIKE";
            case IS_NULL: return "IS NULL";
            case IS_NOT_NULL: return "IS NOT NULL";
            case IN: return "IN (comma-separated)";
            default: return op.name();
        }
    }
}
