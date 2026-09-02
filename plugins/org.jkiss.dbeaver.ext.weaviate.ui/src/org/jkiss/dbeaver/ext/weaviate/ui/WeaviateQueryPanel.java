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
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.events.SelectionEvent;
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
import org.eclipse.swt.widgets.Scale;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.eclipse.swt.widgets.Group;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.ui.forms.events.ExpansionAdapter;
import org.eclipse.ui.forms.events.ExpansionEvent;
import org.eclipse.ui.forms.widgets.ExpandableComposite;
import org.jkiss.dbeaver.ui.controls.ExpandableCompositeEx;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.ui.query.WeaviateFilterSection;
import org.jkiss.dbeaver.ext.weaviate.ui.query.WeaviateGenerativeSection;
import org.jkiss.dbeaver.ext.weaviate.ui.query.WeaviateGroupBySection;
import org.jkiss.dbeaver.ext.weaviate.ui.query.WeaviateQueryBanner;
import org.jkiss.dbeaver.ext.weaviate.ui.query.WeaviateQueryPanelContext;
import org.jkiss.dbeaver.ext.weaviate.ui.query.WeaviateRerankSection;
import org.jkiss.dbeaver.ext.weaviate.ui.query.WeaviateSearchOptionsSection;
import org.jkiss.dbeaver.ext.weaviate.ui.query.WeaviateTargetSection;
import org.jkiss.dbeaver.ext.weaviate.ui.query.WeaviateSectionWidgets;
import org.jkiss.dbeaver.ext.weaviate.ui.query.WeaviateTenantRow;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateFilterRow;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateHybridFusion;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateProperty;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQueryMode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQuerySpec;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateVectorParser;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateVectorTarget;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
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

public class WeaviateQueryPanel extends ResultSetPanelBase implements WeaviateQueryPanelContext {

    public static final String PANEL_ID = "weaviate-query";

    private static final Log log = Log.getLog(WeaviateQueryPanel.class);
    /** Lines of room for a query box. Three fits a sentence-long query without dwarfing the panel. */
    private static final int QUERY_BOX_LINES = 3;

    private static final String NO_FUSION = "(default)";

    private IResultSetPresentation presentation;

    private Combo modeCombo;
    /** Mode the fields currently show, so switching can carry the query text across. */
    private WeaviateQueryMode displayedMode;
    /** One autocut control per mode that supports it; a plain fetch has none. */
    private final Map<WeaviateQueryMode, Spinner> autoCutSpinners = new EnumMap<>(WeaviateQueryMode.class);
    /** One explain-score toggle per mode that can produce an explanation. */
    private final Map<WeaviateQueryMode, Button> explainScoreChecks = new EnumMap<>(WeaviateQueryMode.class);
    private final WeaviateTenantRow tenantRow = new WeaviateTenantRow(this);
    /**
     * Backs the combo, whose items carry the state in their text and so cannot be used as tenant
     * names. Index-aligned with the combo's items.
     */
    /** One include-vectors toggle per mode, beside that mode's other result options. */
    private final Map<WeaviateQueryMode, Button> includeVectorChecks = new EnumMap<>(WeaviateQueryMode.class);
    /** Rerank section per near_* mode: property picker, optional query, module hint. */
    private final WeaviateRerankSection rerankSection = new WeaviateRerankSection(this);
    private final WeaviateGenerativeSection generativeSection = new WeaviateGenerativeSection(this);
    /** Opt-in metadata checkboxes per mode: created / updated / (near_* only) certainty. */
    private final Map<WeaviateQueryMode, Button> createdChecks = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Button> updatedChecks = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Button> certaintyChecks = new EnumMap<>(WeaviateQueryMode.class);
    /** Target-vector section per mode that can carry one; see WeaviateQueryMode#supportsTargetVectors. */
    private final WeaviateTargetSection targetSection = new WeaviateTargetSection(this);
    /** Guards reflow against the resize it can itself provoke. */
    private boolean reflowing;
    /** Whether the collection declares enough named vectors for a target to be a real choice. */
    private ScrolledComposite scroller;
    private Composite content;
    private Composite fieldsHolder;
    private Composite emptyComposite;
    private Composite bm25Composite;
    private Text bm25QueryField;
    private org.eclipse.swt.widgets.List bm25PropertiesList;
    private Composite nearTextComposite;
    private Text nearTextQueryField;
    private Text nearTextDistanceField;
    private Composite nearVectorComposite;
    private Label nearVectorLabel;
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
    private final WeaviateQueryBanner banner = new WeaviateQueryBanner(this::clearRememberedError);
    private final WeaviateFilterSection filterSection = new WeaviateFilterSection(this);
    private final WeaviateSearchOptionsSection searchOptionsSection =
        new WeaviateSearchOptionsSection(this);
    private final WeaviateGroupBySection groupBySection = new WeaviateGroupBySection(this);
    /** The property label and combo, hidden together where grouping does not apply. */

    public WeaviateQueryPanel() {
    }

    @Override
    public Control createContents(IResultSetPresentation presentation, Composite parent) {
        this.presentation = presentation;

        // A fresh viewer must not re-fire a search or generative task remembered from the last
        // one -- opening a tab is browsing, not running. Disarmed, the first read demotes the
        // remembered spec to a plain fetch (keeping its inputs); Run below arms it again.
        WeaviateCollection openedCollection = currentCollection();
        if (openedCollection != null) {
            openedCollection.disarmRun();
        }

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

        tenantRow.createControls(root);

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

        banner.createControls(root);

        // Everything below the mode row scrolls. The sections stack rather than share the space --
        // mode inputs, then target vectors, then filters, with reranker and generative to follow --
        // so on a short panel the later ones have to be reachable rather than clipped.
        scroller = new ScrolledComposite(root, SWT.V_SCROLL);
        scroller.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        scroller.setExpandHorizontal(true);
        scroller.setExpandVertical(true);

        // Narrowing the panel makes wrapped content taller, so the scroll extent has to follow.
        scroller.addListener(SWT.Resize, e -> reflow());

        content = new Composite(scroller, SWT.NONE);
        GridLayout contentLayout = new GridLayout(1, false);
        contentLayout.marginWidth = 0;
        contentLayout.marginHeight = 0;
        content.setLayout(contentLayout);
        scroller.setContent(content);

        fieldsHolder = new Composite(content, SWT.NONE);
        // Not a StackLayout. That sizes to the tallest mode, so every mode was as tall as the
        // roomiest one, and it reported a stale height when a target row was added -- which is how
        // the mode panel came to overlap the filters below it. Showing one child and excluding the
        // rest lets the holder size to the mode actually on screen, and re-excluding is what
        // reflow() then measures.
        fieldsHolder.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        GridLayout holderLayout = new GridLayout(1, false);
        holderLayout.marginWidth = 0;
        holderLayout.marginHeight = 0;
        fieldsHolder.setLayout(holderLayout);

        emptyComposite = createEmptyFields(fieldsHolder);
        bm25Composite = createBm25Fields(fieldsHolder);
        nearTextComposite = createNearTextFields(fieldsHolder);
        nearVectorComposite = createNearVectorFields(fieldsHolder);
        nearObjectComposite = createNearObjectFields(fieldsHolder);
        hybridComposite = createHybridFields(fieldsHolder);
        for (Composite mode : modeComposites()) {
            mode.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        }

        searchOptionsSection.createControls(content);
        filterSection.createControls(content);
        groupBySection.createControls(content);

        // Must run here as well as in activatePanel(): the row starts hidden, and on first
        // display of the panel activatePanel() has not necessarily fired yet, so without this
        // the tenant picker never appears for a multi-tenant collection.
        tenantRow.refresh();
        targetSection.refresh();
        rerankSection.refresh();
        generativeSection.refresh();
        loadSpecIntoUi();
        updateFieldVisibility();
        refreshStatusFromCollection();

        return root;
    }

    /**
     * A collapsible section with a twistie and a title, returning the composite to fill.
     * <p>
     * The shape every optional block of the panel takes -- target vectors, filters, and the
     * reranker and generative blocks still to come. Collapsed sections cost one line each, which
     * is what keeps the panel usable once there are several; the expansion state is remembered per
     * section, so whichever ones you work with stay open across sessions.
     *
     * @param persistKey identity under which the expansion state is stored; stable per section
     */
    @NotNull
    @Override
    public Composite createSection(
        @NotNull Composite parent,
        @NotNull String title,
        @NotNull String persistKey,
        int columns,
        boolean expandedByDefault
    ) {
        ExpandableCompositeEx section = UIUtils.createExpandableCompositeWithSeparator(
            parent, ExpandableComposite.CLIENT_INDENT, ExpandableComposite.TWISTIE);
        section.setText(title);
        section.setShowTextAsTitle(true);
        section.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        // Seed the stored state before handing over the key. setPersistenceKey restores whatever
        // is on file, and an absent setting reads as false -- so without this an expanded-by-default
        // section would collapse itself the moment it became persistent.
        IDialogSettings settings = UIUtils.getDialogSettings(ExpandableCompositeEx.class.getName());
        if (settings.get(persistKey) == null) {
            settings.put(persistKey, expandedByDefault);
        }
        section.setPersistenceKey(persistKey);

        Composite client = new Composite(section, SWT.NONE);
        GridLayout gl = new GridLayout(columns, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        client.setLayout(gl);
        section.setClient(client);
        // Folding changes the height of everything below it, so the scroller has to be re-measured.
        section.addExpansionListener(new ExpansionAdapter() {
            @Override
            public void expansionStateChanged(ExpansionEvent e) {
                reflow();
            }
        });
        return client;
    }

    @NotNull
    private List<Composite> modeComposites() {
        return List.of(emptyComposite, bm25Composite, nearTextComposite,
            nearVectorComposite, nearObjectComposite, hybridComposite);
    }

    /**
     * Re-measure the whole scrollable area after anything changes height.
     * <p>
     * One entry point on purpose. Laying out only the composite that changed leaves its ancestors
     * holding the old preferred size, which is how a grown mode panel came to be drawn over the
     * section beneath it instead of pushing it down.
     */
    /**
     * Show how many rows a section holds alongside its title.
     * <p>
     * The point of the count is the collapsed state: folded shut, the title is all there is, and
     * "Filters" alone cannot say whether anything is filtering. Left bare rather than "(0)" when
     * empty -- nothing configured is the ordinary case, and a zero on every section is noise.
     *
     * @param client the composite handed back by {@link #createSection}, whose parent is the
     *               section carrying the title
     */
    @Override
    public void setSectionCount(@Nullable Composite client, @NotNull String title, int count) {
        if (client == null || client.isDisposed()
            || !(client.getParent() instanceof ExpandableCompositeEx section)
            || section.isDisposed()
        ) {
            return;
        }
        section.setText(count > 0 ? title + " (" + count + ")" : title);
        // The title is part of the section's own layout, and a longer one can need more width.
        section.layout(true, true);
        reflow();
    }

    @Override
    public void onTargetsAvailable(boolean available) {
        // The lone vector box is how a single-vector collection says which space to search. Once
        // targets are on offer every vector belongs to one of them, so it would be a second,
        // contradictory way to say the same thing.
        setNearVectorRowVisible(!available);
    }

    private void setNearVectorRowVisible(boolean visible) {
        for (Control control : new Control[]{nearVectorLabel, nearVectorField}) {
            if (control == null || control.isDisposed()) {
                continue;
            }
            control.setVisible(visible);
            ((GridData) control.getLayoutData()).exclude = !visible;
        }
    }

    @Override
    public void reflow() {
        if (reflowing || content == null || content.isDisposed() || scroller == null || scroller.isDisposed()) {
            return;
        }
        reflowing = true;
        try {
            content.layout(true, true);
            // Width drives how the wrapping labels and fill fields report their height, so the
            // minimum has to be measured at the width the content actually gets. Before the first
            // layout there is no client area yet; DEFAULT then means "as wide as you like", which
            // is the only honest answer until there is a width to measure against.
            int width = scroller.getClientArea().width;
            scroller.setMinSize(content.computeSize(width > 0 ? width : SWT.DEFAULT, SWT.DEFAULT));
            content.getParent().layout(true, true);
        } finally {
            reflowing = false;
        }
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
        addMetadataFields(c, WeaviateQueryMode.FETCH);
        generativeSection.addTo(c, WeaviateQueryMode.FETCH);
        l.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        return c;
    }

    private Composite createBm25Fields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_query);
        bm25QueryField = new Text(c, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        bm25QueryField.setLayoutData(fillFieldData());
        bm25QueryField.setMessage(WeaviateUIMessages.query_search_hint);
        WeaviateSectionWidgets.setVisibleLines(bm25QueryField, QUERY_BOX_LINES);
        bm25QueryField.setToolTipText(NLS.bind(WeaviateUIMessages.query_multiline_tip,
            WeaviateSectionWidgets.modEnterLabel()));
        WeaviateSectionWidgets.runOnModEnter(bm25QueryField, this::runQuery);

        Label lbl = new Label(c, SWT.NONE);
        lbl.setText(WeaviateUIMessages.query_properties);
        lbl.setLayoutData(labelTopData());
        bm25PropertiesList = new org.eclipse.swt.widgets.List(c, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL);
        GridData lgd = new GridData(SWT.FILL, SWT.FILL, true, true);
        lgd.heightHint = 80;
        bm25PropertiesList.setLayoutData(lgd);
        bm25PropertiesList.setToolTipText(WeaviateUIMessages.query_bm25_properties_tip);
        addIncludeVectorField(c, WeaviateQueryMode.BM25);
        addMetadataFields(c, WeaviateQueryMode.BM25);
        addExplainScoreField(c, WeaviateQueryMode.BM25);
        addAutoCutField(c, WeaviateQueryMode.BM25);
        generativeSection.addTo(c, WeaviateQueryMode.BM25);
        return c;
    }

    private Composite createNearTextFields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_query);
        nearTextQueryField = new Text(c, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        nearTextQueryField.setLayoutData(fillFieldData());
        nearTextQueryField.setMessage(WeaviateUIMessages.query_search_hint);
        WeaviateSectionWidgets.setVisibleLines(nearTextQueryField, QUERY_BOX_LINES);
        nearTextQueryField.setToolTipText(NLS.bind(WeaviateUIMessages.query_multiline_tip,
            WeaviateSectionWidgets.modEnterLabel()));
        WeaviateSectionWidgets.runOnModEnter(nearTextQueryField, this::runQuery);

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_distance);
        nearTextDistanceField = new Text(c, SWT.BORDER);
        nearTextDistanceField.setLayoutData(fillFieldData());
        nearTextDistanceField.setMessage(WeaviateUIMessages.query_distance_hint);
        runOnEnter(nearTextDistanceField);
        addIncludeVectorField(c, WeaviateQueryMode.NEAR_TEXT);
        addMetadataFields(c, WeaviateQueryMode.NEAR_TEXT);
        addAutoCutField(c, WeaviateQueryMode.NEAR_TEXT);
        targetSection.addTo(c, WeaviateQueryMode.NEAR_TEXT);
        rerankSection.addTo(c, WeaviateQueryMode.NEAR_TEXT);
        generativeSection.addTo(c, WeaviateQueryMode.NEAR_TEXT);
        return c;
    }

    private Composite createNearVectorFields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        nearVectorLabel = new Label(c, SWT.NONE);
        nearVectorLabel.setText(WeaviateUIMessages.query_vector);
        nearVectorLabel.setLayoutData(labelTopData());
        nearVectorField = new Text(c, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.WRAP);
        WeaviateSectionWidgets.runOnModEnter(nearVectorField, this::runQuery);
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
        addMetadataFields(c, WeaviateQueryMode.NEAR_VECTOR);
        addAutoCutField(c, WeaviateQueryMode.NEAR_VECTOR);
        targetSection.addTo(c, WeaviateQueryMode.NEAR_VECTOR);
        rerankSection.addTo(c, WeaviateQueryMode.NEAR_VECTOR);
        generativeSection.addTo(c, WeaviateQueryMode.NEAR_VECTOR);
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
        addMetadataFields(c, WeaviateQueryMode.NEAR_OBJECT);
        addAutoCutField(c, WeaviateQueryMode.NEAR_OBJECT);
        rerankSection.addTo(c, WeaviateQueryMode.NEAR_OBJECT);
        generativeSection.addTo(c, WeaviateQueryMode.NEAR_OBJECT);
        return c;
    }

    private Composite createHybridFields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        new Label(c, SWT.NONE).setText(WeaviateUIMessages.query_query);
        hybridQueryField = new Text(c, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        hybridQueryField.setLayoutData(fillFieldData());
        hybridQueryField.setMessage(WeaviateUIMessages.query_search_hint);
        WeaviateSectionWidgets.setVisibleLines(hybridQueryField, QUERY_BOX_LINES);
        hybridQueryField.setToolTipText(NLS.bind(WeaviateUIMessages.query_multiline_tip,
            WeaviateSectionWidgets.modEnterLabel()));
        WeaviateSectionWidgets.runOnModEnter(hybridQueryField, this::runQuery);

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
        addMetadataFields(c, WeaviateQueryMode.HYBRID);
        addExplainScoreField(c, WeaviateQueryMode.HYBRID);
        addAutoCutField(c, WeaviateQueryMode.HYBRID);
        targetSection.addTo(c, WeaviateQueryMode.HYBRID);
        generativeSection.addTo(c, WeaviateQueryMode.HYBRID);
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

    /**
     * Adds the opt-in metadata checkboxes to a mode's own field panel, beside the other options
     * that decide what comes back. Certainty appears only where the mode can produce one --
     * same reasoning as the explain-score toggle.
     */
    private void addMetadataFields(@NotNull Composite c, @NotNull WeaviateQueryMode mode) {
        Label label = new Label(c, SWT.NONE);
        label.setText(WeaviateUIMessages.query_metadata);
        label.setToolTipText(WeaviateUIMessages.query_metadata_tip);

        Composite row = new Composite(c, SWT.NONE);
        GridLayout gl = new GridLayout(3, false);
        gl.marginWidth = 0;
        gl.marginHeight = 0;
        row.setLayout(gl);
        row.setLayoutData(fillFieldData());

        Button created = new Button(row, SWT.CHECK);
        created.setText(WeaviateUIMessages.query_metadata_created);
        createdChecks.put(mode, created);

        Button updated = new Button(row, SWT.CHECK);
        updated.setText(WeaviateUIMessages.query_metadata_updated);
        updatedChecks.put(mode, updated);

        if (mode.supportsCertainty()) {
            Button certainty = new Button(row, SWT.CHECK);
            certainty.setText(WeaviateUIMessages.query_metadata_certainty);
            certaintyChecks.put(mode, certainty);
        }
    }

    private boolean currentCheck(@NotNull Map<WeaviateQueryMode, Button> checks) {
        Button check = checks.get(currentMode());
        return check != null && !check.isDisposed() && check.getSelection();
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
    /**
     * Run the query from a single-line field, on Enter or on the commit chord.
     * <p>
     * Both, so the chord means the same thing in every box. A multi-line query box cannot take
     * Enter -- it needs it to break the line -- and a shortcut that works in some fields and not
     * others is worse than one that always works.
     */
    private void runOnEnter(@NotNull Text field) {
        field.addListener(SWT.DefaultSelection, e -> runQuery());
        WeaviateSectionWidgets.runOnModEnter(field, this::runQuery);
    }

    @Override
    @NotNull
    public WeaviateQueryMode currentMode() {
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
        // Group-by is a shared section, so a mode change has to re-evaluate it here; the
        // per-mode sections are swapped wholesale below instead.
        groupBySection.syncVisibility();
        // Shared too, and for a sharper reason: two of its four options exist for some modes
        // only, so a mode change decides which controls are there at all.
        searchOptionsSection.syncVisibility();

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
        for (Composite candidate : modeComposites()) {
            if (candidate == null || candidate.isDisposed()) {
                continue;
            }
            boolean showing = candidate == top;
            candidate.setVisible(showing);
            ((GridData) candidate.getLayoutData()).exclude = !showing;
        }
        reflow();
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

    @Override
    @NotNull
    public List<String> currentPropertyNames() {
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

    private void loadAutoCutIntoUi(@NotNull WeaviateQuerySpec spec) {
        // All modes, not just the spec's: these options are per-mode widgets over one shared
        // value each, and a demoted spec must leave them configured everywhere.
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            Spinner spinner = autoCutSpinners.get(mode);
            if (spinner != null && !spinner.isDisposed()) {
                spinner.setSelection(spec.getAutoCut() == null ? 0 : spec.getAutoCut());
            }
            WeaviateSectionWidgets.setChecked(explainScoreChecks.get(mode), spec.isExplainScore());
            WeaviateSectionWidgets.setChecked(includeVectorChecks.get(mode), spec.isIncludeVector());
            WeaviateSectionWidgets.setChecked(createdChecks.get(mode), spec.isWithCreated());
            WeaviateSectionWidgets.setChecked(updatedChecks.get(mode), spec.isWithUpdated());
            WeaviateSectionWidgets.setChecked(certaintyChecks.get(mode), spec.isWithCertainty());
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
        targetSection.loadFrom(spec);
        rerankSection.loadFrom(spec);
        generativeSection.loadFrom(spec);
        groupBySection.loadFrom(spec);


        searchOptionsSection.loadFrom(spec);
        filterSection.loadFrom(spec);

        // Every mode's inputs load from the one spec, not just the current mode's. A spec
        // demoted to Fetch on viewer-open still carries the remembered search inputs, and
        // "the options stay configured" means they are there when the user switches back --
        // the same sharing carryQueryText already does for the query text.
        if (spec.getQuery() != null) {
            bm25QueryField.setText(spec.getQuery());
            nearTextQueryField.setText(spec.getQuery());
            hybridQueryField.setText(spec.getQuery());
        }
        String distance = spec.getDistance() == null ? null : spec.getDistance().toString();
        if (distance != null) {
            nearTextDistanceField.setText(distance);
            nearVectorDistanceField.setText(distance);
            nearObjectDistanceField.setText(distance);
        }
        if (spec.getVector() != null) {
            nearVectorField.setText(WeaviateVectorParser.format(spec.getVector()));
        }
        if (spec.getObjectId() != null) {
            nearObjectField.setText(spec.getObjectId());
        }
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
        if (spec.getAlpha() != null) {
            hybridAlphaScale.setSelection(Math.round(spec.getAlpha() * 100));
            updateAlphaLabel();
        }
        if (spec.getFusionType() != null) {
            hybridFusionCombo.setText(spec.getFusionType().name());
        } else {
            hybridFusionCombo.select(0);
        }
    }

    @Override
    public void runQuery() {
        WeaviateCollection collection = currentCollection();
        if (collection == null) {
            banner.showError(WeaviateUIMessages.query_not_weaviate);
            return;
        }
        WeaviateQuerySpec spec;
        try {
            spec = buildSpec();
        } catch (IllegalArgumentException e) {
            banner.showError(e.getMessage());
            return;
        }
        collection.setQuerySpec(spec);
        collection.armRun();
        collection.clearLastQueryError();
        banner.showInfo(WeaviateUIMessages.query_running);
        presentation.getController().refreshData(() -> Display.getDefault().asyncExec(this::refreshStatusFromCollection));
    }

    private void resetToFetch() {
        WeaviateCollection collection = currentCollection();
        if (collection == null) {
            banner.showError(WeaviateUIMessages.query_not_weaviate);
            return;
        }
        collection.setQuerySpec(WeaviateQuerySpec.fetch());
        collection.armRun();
        collection.clearLastQueryError();
        modeCombo.select(WeaviateQueryMode.FETCH.ordinal());
        updateFieldVisibility();
        banner.showInfo(WeaviateUIMessages.query_running);
        presentation.getController().refreshData(() -> Display.getDefault().asyncExec(this::refreshStatusFromCollection));
    }

    private WeaviateQuerySpec buildSpec() {
        WeaviateQueryMode mode = currentMode();
        // Collected before the switch: Near Vector reads them to decide whether its own vector
        // box is required at all.
        List<WeaviateVectorTarget> targets = mode.supportsTargetVectors()
            ? targetSection.currentTargets(mode)
            : Collections.emptyList();
        List<WeaviateFilterRow> rows = filterSection.collectRows();
        boolean any = filterSection.isAnyFilter();
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
                Float distance = parseOptionalFloat(nearVectorDistanceField.getText(), "distance");
                builder = WeaviateQuerySpec.builder(WeaviateQueryMode.NEAR_VECTOR)
                    .distance(distance);
                // With targets every vector arrives attached to the space it belongs in and the
                // lone box is hidden; without them there is one vector and the server picks.
                if (targets.isEmpty()) {
                    float[] vec;
                    try {
                        vec = WeaviateVectorParser.parse(nearVectorField.getText());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException(
                            NLS.bind(WeaviateUIMessages.query_invalid_vector, e.getMessage()));
                    }
                    if (vec == null || vec.length == 0) {
                        throw new IllegalArgumentException(WeaviateUIMessages.query_required_vector);
                    }
                    builder.vector(vec);
                }
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
            .tenant(tenantRow.currentTenant())
            .autoCut(currentAutoCut())
            .explainScore(currentExplainScore())
            .includeVector(currentIncludeVector())
            .rerank(rerankSection.currentRerank(mode))
            .generative(generativeSection.currentGenerative(mode))
            .withCreated(currentCheck(createdChecks))
            .withUpdated(currentCheck(updatedChecks))
            .withCertainty(currentCheck(certaintyChecks))
            .groupBy(groupBySection.currentGroupBy())
            .consistencyLevel(searchOptionsSection.currentConsistencyLevel())
            .searchOperator(searchOptionsSection.currentSearchOperator())
            .minimumOrTokens(searchOptionsSection.currentMinimumOrTokens())
            .diversity(searchOptionsSection.currentDiversity())
            .withQueryProfile(searchOptionsSection.isWithQueryProfile())
            .targets(targets)
            // Only sent when there is more than one target to join; with one there is nothing
            // to join and the model leaves the strategy off the request entirely.
            .combination(targets.size() > 1 ? targetSection.currentCombination(mode) : null)
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

    @Override
    @Nullable
    public WeaviateCollection currentCollection() {
        if (presentation == null || presentation.getController() == null) return null;
        Object source = presentation.getController().getModel().getSingleSource();
        if (source instanceof WeaviateCollection wc) return wc;
        return null;
    }

    @Override
    public void showError(@Nullable String message) {
        if (message != null) {
            banner.showError(message);
        }
    }

    /** Closes the banner and forgets the error behind it. */
    @Override
    public void dismissBanner() {
        clearRememberedError();
        banner.hide();
    }

    /**
     * Forgets the error the collection is holding, so dismissing the banner sticks rather than
     * having it reappear on the next refresh.
     */
    private void clearRememberedError() {
        WeaviateCollection collection = currentCollection();
        if (collection != null) {
            collection.clearLastQueryError();
        }
    }

    private void refreshStatusFromCollection() {
        generativeSection.refreshResult();
        WeaviateCollection collection = currentCollection();
        if (collection == null) {
            banner.hide();
            return;
        }
        String err = collection.getLastQueryError();
        if (err != null) {
            banner.showError(err);
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
            banner.hide();
        }
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public void activatePanel() {
        tenantRow.refresh();
        targetSection.refresh();
        rerankSection.refresh();
        generativeSection.refresh();
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
        tenantRow.refresh();
        targetSection.refresh();
        rerankSection.refresh();
        generativeSection.refresh();
        // refreshTenants ran before the read completed the first time round, so re-sync after.
        tenantRow.syncSelection();
        loadSpecIntoUi();
        updateFieldVisibility();
        refreshStatusFromCollection();
    }

    @Override
    public void contributeActions(IContributionManager manager) {
        // No toolbar actions for now.
    }

}
