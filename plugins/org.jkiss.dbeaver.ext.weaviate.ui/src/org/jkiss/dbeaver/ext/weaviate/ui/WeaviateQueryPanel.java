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
import org.eclipse.jface.dialogs.IDialogSettings;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.ui.forms.events.ExpansionAdapter;
import org.eclipse.ui.forms.events.ExpansionEvent;
import org.eclipse.ui.forms.widgets.ExpandableComposite;
import org.jkiss.dbeaver.ui.controls.ExpandableCompositeEx;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateColumns;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateFilterOperator;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateFilterRow;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateFilterTranslator;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateHybridFusion;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateProperty;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQueryMode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQuerySpec;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateGenerativeProvider;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateGenerativeTask;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRerankSpec;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateVectorCombination;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateVectorParser;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateVectorTarget;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateVectorizer;
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
    /** Rerank section per near_* mode: property picker, optional query, module hint. */
    private final Map<WeaviateQueryMode, Composite> rerankGroups = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Combo> rerankPropertyCombos = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Text> rerankQueryFields = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Label> rerankModuleLabels = new EnumMap<>(WeaviateQueryMode.class);
    /** Generative section per mode: prompts, provider override, and the grouped-result box. */
    private final Map<WeaviateQueryMode, Composite> generativeGroups = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Text> generativeSingleFields = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Text> generativeGroupedFields = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, org.eclipse.swt.widgets.List> generativePropertyLists = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Combo> generativeProviderCombos = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Text> generativeModelFields = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Text> generativeTemperatureFields = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Text> generativeMaxTokensFields = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Button> generativeMetadataChecks = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Text> generativeResultFields = new EnumMap<>(WeaviateQueryMode.class);
    /** Section rows revealed once generation is switched on, and once a provider is named. */
    private final Map<WeaviateQueryMode, List<Control>> generativeWhenGenerating = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, List<Control>> generativeWhenProviderNamed = new EnumMap<>(WeaviateQueryMode.class);
    /** Opt-in metadata checkboxes per mode: created / updated / (near_* only) certainty. */
    private final Map<WeaviateQueryMode, Button> createdChecks = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Button> updatedChecks = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Button> certaintyChecks = new EnumMap<>(WeaviateQueryMode.class);
    /** Target-vector section per mode that can carry one; see WeaviateQueryMode#supportsTargetVectors. */
    private final Map<WeaviateQueryMode, Composite> targetGroups = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Combo> targetJoinCombos = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Composite> targetRowsHolders = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Button> targetAddButtons = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, java.util.List<TargetRowUi>> targetRowUis = new EnumMap<>(WeaviateQueryMode.class);
    /** Guards reflow against the resize it can itself provoke. */
    private boolean reflowing;
    /** Whether the collection declares enough named vectors for a target to be a real choice. */
    private boolean targetsAvailable;
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
    private Composite banner;
    private Label bannerLabel;
    private Color errorBg;
    private Color infoBg;
    private Font bannerFont;
    private Composite filtersGroup;
    private Composite filterRowsHolder;
    private Button filterAndRadio;
    private Button filterOrRadio;
    private final java.util.List<FilterRowUi> filterRowUis = new java.util.ArrayList<>();

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

        createFilterSection(content);

        // Must run here as well as in activatePanel(): the row starts hidden, and on first
        // display of the panel activatePanel() has not necessarily fired yet, so without this
        // the tenant picker never appears for a multi-tenant collection.
        refreshTenants();
        refreshTargetSections();
        refreshRerankSections();
        refreshGenerativeSections();
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
    private Composite createSection(
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
    private void setSectionCount(@Nullable Composite client, @NotNull String title, int count) {
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

    private void updateFilterCount() {
        setSectionCount(filtersGroup, WeaviateUIMessages.query_filters, filterRowUis.size());
    }

    /**
     * Per mode, because the target section is: each mode owns its own rows, so each carries its
     * own count. It matters more here than on the filters -- a search of a collection with several
     * named vectors has to name a target, so "(0)" folded away is a query that will not run.
     */
    /**
     * Leave each row's dropdown offering only the vectors no other row has taken, plus its own.
     * <p>
     * A vector can only be targeted once -- naming it twice is not a heavier weighting, it is a
     * malformed query -- so the choice is removed rather than offered and then rejected. The
     * Add button switches off once every vector is spoken for, since the row it would add could
     * name nothing.
     */
    private void syncTargetChoices(@NotNull WeaviateQueryMode mode) {
        List<TargetRowUi> rows = targetRowUis.get(mode);
        if (rows == null) {
            return;
        }
        List<String> taken = new ArrayList<>(rows.size());
        for (TargetRowUi row : rows) {
            String name = row.selectedName();
            if (!name.isBlank()) {
                taken.add(name);
            }
        }
        for (TargetRowUi row : rows) {
            row.refreshChoices(taken);
        }
        Button add = targetAddButtons.get(mode);
        if (add != null && !add.isDisposed()) {
            add.setEnabled(rows.size() < currentVectorizers().size());
        }
    }

    /**
     * Vector names no row has claimed yet, in declared order. Seeds a new row so it opens on a
     * free choice rather than on one already in use.
     */
    @NotNull
    private List<String> freeTargetNames(@NotNull WeaviateQueryMode mode) {
        List<TargetRowUi> rows = targetRowUis.get(mode);
        List<String> free = new ArrayList<>();
        for (WeaviateVectorizer vectorizer : currentVectorizers()) {
            free.add(vectorizer.getVectorName());
        }
        if (rows != null) {
            for (TargetRowUi row : rows) {
                free.remove(row.selectedName());
            }
        }
        return free;
    }

    private void updateTargetCount(@NotNull WeaviateQueryMode mode) {
        List<TargetRowUi> rows = targetRowUis.get(mode);
        setSectionCount(targetGroups.get(mode), WeaviateUIMessages.query_targets,
            rows == null ? 0 : rows.size());
    }

    private void reflow() {
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
        addGenerativeField(c, WeaviateQueryMode.FETCH);
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
        addMetadataFields(c, WeaviateQueryMode.BM25);
        addExplainScoreField(c, WeaviateQueryMode.BM25);
        addAutoCutField(c, WeaviateQueryMode.BM25);
        addGenerativeField(c, WeaviateQueryMode.BM25);
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
        addMetadataFields(c, WeaviateQueryMode.NEAR_TEXT);
        addAutoCutField(c, WeaviateQueryMode.NEAR_TEXT);
        addTargetVectorField(c, WeaviateQueryMode.NEAR_TEXT);
        addRerankField(c, WeaviateQueryMode.NEAR_TEXT);
        addGenerativeField(c, WeaviateQueryMode.NEAR_TEXT);
        return c;
    }

    private Composite createNearVectorFields(Composite parent) {
        Composite c = new Composite(parent, SWT.NONE);
        c.setLayout(twoColumnLayout());

        nearVectorLabel = new Label(c, SWT.NONE);
        nearVectorLabel.setText(WeaviateUIMessages.query_vector);
        nearVectorLabel.setLayoutData(labelTopData());
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
        addMetadataFields(c, WeaviateQueryMode.NEAR_VECTOR);
        addAutoCutField(c, WeaviateQueryMode.NEAR_VECTOR);
        addTargetVectorField(c, WeaviateQueryMode.NEAR_VECTOR);
        addRerankField(c, WeaviateQueryMode.NEAR_VECTOR);
        addGenerativeField(c, WeaviateQueryMode.NEAR_VECTOR);
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
        addRerankField(c, WeaviateQueryMode.NEAR_OBJECT);
        addGenerativeField(c, WeaviateQueryMode.NEAR_OBJECT);
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
        addMetadataFields(c, WeaviateQueryMode.HYBRID);
        addExplainScoreField(c, WeaviateQueryMode.HYBRID);
        addAutoCutField(c, WeaviateQueryMode.HYBRID);
        addTargetVectorField(c, WeaviateQueryMode.HYBRID);
        addGenerativeField(c, WeaviateQueryMode.HYBRID);
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

    /**
     * Adds the target-vector section to a mode's own field panel.
     * <p>
     * Per mode like the other options, and only for the modes that can carry a target at all --
     * see {@link WeaviateQueryMode#supportsTargetVectors()}. The section spans both columns of the
     * mode panel, because a row needs more width than a value cell.
     */
    private void addTargetVectorField(@NotNull Composite c, @NotNull WeaviateQueryMode mode) {
        // Expanded by default: on a collection with several named vectors a search has to name a
        // target, so this is a choice to make rather than an extra to go looking for.
        Composite group = createSection(
            c, WeaviateUIMessages.query_targets, "targets." + mode.name(), 1, true);
        group.setToolTipText(WeaviateUIMessages.query_targets_tip);
        ((GridData) group.getParent().getLayoutData()).horizontalSpan = 2;

        Composite header = new Composite(group, SWT.NONE);
        GridLayout hl = new GridLayout(3, false);
        hl.marginWidth = 0;
        hl.marginHeight = 0;
        header.setLayout(hl);
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(header, SWT.NONE).setText(WeaviateUIMessages.query_target_join);
        Combo joinCombo = new Combo(header, SWT.READ_ONLY);
        for (WeaviateVectorCombination combination : WeaviateVectorCombination.values()) {
            joinCombo.add(combination.getLabel());
        }
        joinCombo.select(0);
        joinCombo.setToolTipText(WeaviateUIMessages.query_target_join_tip);
        joinCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                syncTargetWeightsEnabled(mode);
            }
        });

        Button addTarget = new Button(header, SWT.PUSH);
        addTarget.setText(WeaviateUIMessages.query_target_add);
        addTarget.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, true, false));
        addTarget.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                addTargetRow(mode, null);
            }
        });
        targetAddButtons.put(mode, addTarget);

        Composite rowsHolder = new Composite(group, SWT.NONE);
        GridLayout rl = new GridLayout(1, false);
        rl.marginWidth = 0;
        rl.marginHeight = 0;
        rl.verticalSpacing = 3;
        rowsHolder.setLayout(rl);
        rowsHolder.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        targetGroups.put(mode, group);
        targetJoinCombos.put(mode, joinCombo);
        targetRowsHolders.put(mode, rowsHolder);
        targetRowUis.put(mode, new ArrayList<>());
    }

    /**
     * Adds the Generative section to a mode's field panel. Every mode gets one -- the generate
     * client mirrors every query operator, a plain fetch included.
     */
    private void addGenerativeField(@NotNull Composite c, @NotNull WeaviateQueryMode mode) {
        Composite group = createSection(
            c, WeaviateUIMessages.query_generative, "generative." + mode.name(), 2, false);
        group.setToolTipText(WeaviateUIMessages.query_generative_tip);
        ((GridData) group.getParent().getLayoutData()).horizontalSpan = 2;

        // The provider leads, and doubles as the section's on/off switch: with "(no generation)"
        // selected there is nothing to prompt, so the rest of the section is not merely disabled
        // but absent. Choosing anything reveals it.
        new Label(group, SWT.NONE).setText(WeaviateUIMessages.query_generative_provider);
        Combo providerCombo = new Combo(group, SWT.READ_ONLY);
        providerCombo.setLayoutData(fillFieldData());
        providerCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                syncGenerativeVisibility(mode);
                updateGenerativeCount(mode);
            }
        });

        // Shown whenever generation is on at all.
        List<Control> whenGenerating = new ArrayList<>();
        Label singleLabel = new Label(group, SWT.NONE);
        singleLabel.setText(WeaviateUIMessages.query_generative_single);
        Text singleField = new Text(group, SWT.BORDER);
        singleField.setLayoutData(fillFieldData());
        singleField.setMessage(WeaviateUIMessages.query_generative_single_hint);
        singleField.addListener(SWT.Modify, e -> updateGenerativeCount(mode));
        whenGenerating.add(singleLabel);
        whenGenerating.add(singleField);

        Label groupedLabel = new Label(group, SWT.NONE);
        groupedLabel.setText(WeaviateUIMessages.query_generative_grouped);
        Text groupedField = new Text(group, SWT.BORDER);
        groupedField.setLayoutData(fillFieldData());
        groupedField.setMessage(WeaviateUIMessages.query_generative_grouped_hint);
        groupedField.addListener(SWT.Modify, e -> updateGenerativeCount(mode));
        whenGenerating.add(groupedLabel);
        whenGenerating.add(groupedField);

        Label propsLabel = new Label(group, SWT.NONE);
        propsLabel.setText(WeaviateUIMessages.query_generative_properties);
        propsLabel.setLayoutData(labelTopData());
        org.eclipse.swt.widgets.List propertyList =
            new org.eclipse.swt.widgets.List(group, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL);
        GridData plGd = new GridData(SWT.FILL, SWT.FILL, true, false);
        plGd.heightHint = 60;
        propertyList.setLayoutData(plGd);
        propertyList.setToolTipText(WeaviateUIMessages.query_generative_properties_tip);
        whenGenerating.add(propsLabel);
        whenGenerating.add(propertyList);

        // Shown only for a named provider: with the collection default the server never sees
        // these, so they would be three boxes whose values go nowhere.
        List<Control> whenProviderNamed = new ArrayList<>();
        Label modelLabel = new Label(group, SWT.NONE);
        modelLabel.setText(WeaviateUIMessages.query_generative_model);
        Text modelField = new Text(group, SWT.BORDER);
        modelField.setLayoutData(fillFieldData());
        whenProviderNamed.add(modelLabel);
        whenProviderNamed.add(modelField);

        Label temperatureLabel = new Label(group, SWT.NONE);
        temperatureLabel.setText(WeaviateUIMessages.query_generative_temperature);
        Text temperatureField = new Text(group, SWT.BORDER);
        temperatureField.setLayoutData(fillFieldData());
        whenProviderNamed.add(temperatureLabel);
        whenProviderNamed.add(temperatureField);

        Label maxTokensLabel = new Label(group, SWT.NONE);
        maxTokensLabel.setText(WeaviateUIMessages.query_generative_max_tokens);
        Text maxTokensField = new Text(group, SWT.BORDER);
        maxTokensField.setLayoutData(fillFieldData());
        whenProviderNamed.add(maxTokensLabel);
        whenProviderNamed.add(maxTokensField);

        Label metadataSpacer = new Label(group, SWT.NONE);
        metadataSpacer.setText("");
        Button metadataCheck = new Button(group, SWT.CHECK);
        metadataCheck.setText(WeaviateUIMessages.query_generative_metadata);
        metadataCheck.setToolTipText(WeaviateUIMessages.query_generative_metadata_tip);
        whenGenerating.add(metadataSpacer);
        whenGenerating.add(metadataCheck);

        Label resultLabel = new Label(group, SWT.NONE);
        resultLabel.setText(WeaviateUIMessages.query_generative_grouped_result);
        resultLabel.setLayoutData(labelTopData());
        // Read-only rather than disabled: disabled text cannot be selected or copied, and
        // copying the generated answer out is half the point of showing it.
        Text resultField = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL | SWT.READ_ONLY);
        GridData rfGd = new GridData(SWT.FILL, SWT.FILL, true, false);
        rfGd.heightHint = 60;
        resultField.setLayoutData(rfGd);
        whenGenerating.add(resultLabel);
        whenGenerating.add(resultField);

        generativeGroups.put(mode, group);
        generativeSingleFields.put(mode, singleField);
        generativeGroupedFields.put(mode, groupedField);
        generativePropertyLists.put(mode, propertyList);
        generativeProviderCombos.put(mode, providerCombo);
        generativeModelFields.put(mode, modelField);
        generativeTemperatureFields.put(mode, temperatureField);
        generativeMaxTokensFields.put(mode, maxTokensField);
        generativeMetadataChecks.put(mode, metadataCheck);
        generativeResultFields.put(mode, resultField);
        generativeWhenGenerating.put(mode, whenGenerating);
        generativeWhenProviderNamed.put(mode, whenProviderNamed);
        syncGenerativeVisibility(mode);
    }

    /**
     * Refill the provider dropdowns and property lists from the collection the panel is bound
     * to. The first provider entry is the collection's own generative module, named so the
     * default is a visible choice rather than a blank.
     */
    private void refreshGenerativeSections() {
        WeaviateCollection collection = currentCollection();
        String moduleKind = null;
        if (collection != null) {
            try {
                moduleKind = collection.getGenerativeModuleKind();
            } catch (Exception e) {
                log.debug("Failed to read the generative module", e);
            }
        }
        String defaultEntry = moduleKind == null
            ? WeaviateUIMessages.query_generative_provider_default_none
            : NLS.bind(WeaviateUIMessages.query_generative_provider_default, moduleKind);
        List<String> properties = currentPropertyNames();
        for (WeaviateQueryMode mode : generativeProviderCombos.keySet()) {
            Combo combo = generativeProviderCombos.get(mode);
            if (combo == null || combo.isDisposed()) {
                continue;
            }
            int selected = combo.getSelectionIndex();
            combo.removeAll();
            combo.add(WeaviateUIMessages.query_generative_provider_none);
            combo.add(defaultEntry);
            for (WeaviateGenerativeProvider provider : WeaviateGenerativeProvider.values()) {
                combo.add(provider.getLabel());
            }
            combo.select(Math.max(0, selected));
            org.eclipse.swt.widgets.List list = generativePropertyLists.get(mode);
            if (list != null && !list.isDisposed()) {
                List<String> keep = List.of(list.getSelection());
                list.removeAll();
                for (String name : properties) {
                    list.add(name);
                }
                for (String name : keep) {
                    int idx = list.indexOf(name);
                    if (idx >= 0) list.select(idx);
                }
            }
            syncGenerativeVisibility(mode);
            updateGenerativeCount(mode);
        }
    }

    /**
     * Reveal as much of the section as the provider choice justifies.
     * <p>
     * Hidden rather than disabled: with no generation chosen there is nothing to prompt, and a
     * column of greyed-out boxes reads as broken rather than as not-applicable. The rows keep
     * their contents while hidden, so flipping the provider back brings the prompts with it.
     */
    private void syncGenerativeVisibility(@NotNull WeaviateQueryMode mode) {
        int idx = providerIndex(mode);
        setRowsVisible(generativeWhenGenerating.get(mode), idx > 0);
        setRowsVisible(generativeWhenProviderNamed.get(mode), idx > 1);
        Composite group = generativeGroups.get(mode);
        if (group != null && !group.isDisposed()) {
            group.layout(true, true);
        }
        reflow();
    }

    private static void setRowsVisible(@Nullable List<Control> rows, boolean visible) {
        if (rows == null) {
            return;
        }
        for (Control control : rows) {
            if (control == null || control.isDisposed()) {
                continue;
            }
            control.setVisible(visible);
            Object data = control.getLayoutData();
            if (data instanceof GridData gd) {
                gd.exclude = !visible;
            } else {
                GridData gd = new GridData();
                gd.exclude = !visible;
                control.setLayoutData(gd);
            }
        }
    }

    private int providerIndex(@NotNull WeaviateQueryMode mode) {
        Combo combo = generativeProviderCombos.get(mode);
        if (combo == null || combo.isDisposed()) {
            return 0;
        }
        return Math.max(0, combo.getSelectionIndex());
    }

    /**
     * The chosen provider override, or null for both "no generation" and "collection default" --
     * neither sends one. Use {@link #providerIndex} to tell those two apart.
     */
    @Nullable
    private WeaviateGenerativeProvider selectedGenerativeProvider(@NotNull WeaviateQueryMode mode) {
        int idx = providerIndex(mode);
        return idx <= 1 ? null : WeaviateGenerativeProvider.values()[idx - 2];
    }

    /** Count = prompts filled in (0-2), so the folded title says whether anything will generate. */
    private void updateGenerativeCount(@NotNull WeaviateQueryMode mode) {
        if (providerIndex(mode) == 0) {
            setSectionCount(generativeGroups.get(mode), WeaviateUIMessages.query_generative, 0);
            return;
        }
        int count = 0;
        Text single = generativeSingleFields.get(mode);
        Text grouped = generativeGroupedFields.get(mode);
        if (single != null && !single.isDisposed() && !single.getText().isBlank()) count++;
        if (grouped != null && !grouped.isDisposed() && !grouped.getText().isBlank()) count++;
        setSectionCount(generativeGroups.get(mode), WeaviateUIMessages.query_generative, count);
    }

    /**
     * The generative task the current mode's section describes, or null for none.
     *
     * @throws IllegalArgumentException with a banner message for a half-configured task, so a
     *                                  request that cannot mean what it says is never sent
     */
    @Nullable
    private WeaviateGenerativeTask currentGenerative(@NotNull WeaviateQueryMode mode) {
        Text singleField = generativeSingleFields.get(mode);
        Text groupedField = generativeGroupedFields.get(mode);
        if (singleField == null || singleField.isDisposed()
            || groupedField == null || groupedField.isDisposed()
        ) {
            return null;
        }
        if (providerIndex(mode) == 0) {
            // Generation switched off. The prompt fields are hidden but keep their text, so this
            // is deliberate rather than a half-filled state worth complaining about.
            return null;
        }
        String single = singleField.getText();
        String grouped = groupedField.getText();
        org.eclipse.swt.widgets.List propertyList = generativePropertyLists.get(mode);
        List<String> groupedProperties = propertyList == null || propertyList.isDisposed()
            ? Collections.emptyList() : List.of(propertyList.getSelection());
        WeaviateGenerativeProvider provider = selectedGenerativeProvider(mode);
        String model = textOf(generativeModelFields.get(mode));
        String temperatureRaw = textOf(generativeTemperatureFields.get(mode));
        String maxTokensRaw = textOf(generativeMaxTokensFields.get(mode));

        if (single.isBlank() && grouped.isBlank()) {
            // Nothing to generate. Leftover provider params are fine -- they cost nothing --
            // but selected task properties suggest a grouped task someone forgot to type.
            if (!groupedProperties.isEmpty()) {
                throw new IllegalArgumentException(
                    WeaviateUIMessages.query_generative_props_without_grouped);
            }
            return null;
        }
        if (grouped.isBlank() && !groupedProperties.isEmpty()) {
            throw new IllegalArgumentException(
                WeaviateUIMessages.query_generative_props_without_grouped);
        }
        Float temperature = null;
        if (!temperatureRaw.isBlank()) {
            try {
                temperature = Float.parseFloat(temperatureRaw.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    NLS.bind(WeaviateUIMessages.query_generative_invalid_temperature, temperatureRaw));
            }
        }
        Integer maxTokens = null;
        if (!maxTokensRaw.isBlank()) {
            try {
                maxTokens = Integer.parseInt(maxTokensRaw.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    NLS.bind(WeaviateUIMessages.query_generative_invalid_max_tokens, maxTokensRaw));
            }
        }
        Button metadataCheck = generativeMetadataChecks.get(mode);
        return WeaviateGenerativeTask.builder()
            .singlePrompt(single)
            .groupedTask(grouped)
            .groupedProperties(groupedProperties)
            .provider(provider)
            .model(model)
            .temperature(temperature)
            .maxTokens(maxTokens)
            .returnMetadata(metadataCheck != null && !metadataCheck.isDisposed()
                && metadataCheck.getSelection())
            .build();
    }

    @NotNull
    private static String textOf(@Nullable Text field) {
        return field == null || field.isDisposed() || field.getText() == null ? "" : field.getText();
    }

    /**
     * Adds the Rerank section to a near_* mode's field panel.
     * <p>
     * Only those modes: client 6.3.0 exposes rerank solely on the vector-search builders, so
     * BM25 and Hybrid -- which the server could rerank -- have nowhere to attach it. The section
     * tooltip says so, and also that the rerank score itself never comes back.
     */
    private void addRerankField(@NotNull Composite c, @NotNull WeaviateQueryMode mode) {
        Composite group = createSection(
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
                updateRerankCount(mode);
            }
        });

        new Label(group, SWT.NONE).setText(WeaviateUIMessages.query_rerank_query);
        Text queryField = new Text(group, SWT.BORDER);
        queryField.setLayoutData(fillFieldData());
        queryField.setMessage(WeaviateUIMessages.query_rerank_query_hint);
        runOnEnter(queryField);

        Label moduleLabel = new Label(group, SWT.WRAP);
        GridData mlGd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        mlGd.horizontalSpan = 2;
        moduleLabel.setLayoutData(mlGd);

        rerankGroups.put(mode, group);
        rerankPropertyCombos.put(mode, propertyCombo);
        rerankQueryFields.put(mode, queryField);
        rerankModuleLabels.put(mode, moduleLabel);
    }

    /**
     * Refill the rerank property pickers and the module hint from the collection the panel is
     * bound to. "(none)" leads each list so a chosen property can be un-chosen.
     */
    private void refreshRerankSections() {
        List<String> properties = currentPropertyNames();
        String moduleHint = rerankModuleHint();
        for (WeaviateQueryMode mode : rerankPropertyCombos.keySet()) {
            Combo combo = rerankPropertyCombos.get(mode);
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
            Label moduleLabel = rerankModuleLabels.get(mode);
            if (moduleLabel != null && !moduleLabel.isDisposed()) {
                moduleLabel.setText(moduleHint);
            }
            updateRerankCount(mode);
        }
    }

    /**
     * Name the collection's reranker module, or warn that there is none -- a reranked query then
     * fails, and better the section says so than the server's error explains it after the fact.
     */
    @NotNull
    private String rerankModuleHint() {
        WeaviateCollection collection = currentCollection();
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

    private void updateRerankCount(@NotNull WeaviateQueryMode mode) {
        setSectionCount(rerankGroups.get(mode), WeaviateUIMessages.query_rerank,
            selectedRerankProperty(mode) == null ? 0 : 1);
    }

    /** The picked rerank property, or null while "(none)" is selected. */
    @Nullable
    private String selectedRerankProperty(@NotNull WeaviateQueryMode mode) {
        Combo combo = rerankPropertyCombos.get(mode);
        if (combo == null || combo.isDisposed() || combo.getSelectionIndex() <= 0) {
            return null;
        }
        return combo.getText();
    }

    /**
     * The rerank request the current mode's section describes, or null for none.
     *
     * @throws IllegalArgumentException when a rerank query was typed but no property picked --
     *                                  the query alone cannot be sent, and dropping it silently
     *                                  would run a different search than the one on screen
     */
    @Nullable
    private WeaviateRerankSpec currentRerank(@NotNull WeaviateQueryMode mode) {
        if (!mode.supportsRerank()) {
            return null;
        }
        String property = selectedRerankProperty(mode);
        Text queryField = rerankQueryFields.get(mode);
        String query = queryField == null || queryField.isDisposed() ? "" : queryField.getText();
        if (property == null) {
            if (!query.isBlank()) {
                throw new IllegalArgumentException(WeaviateUIMessages.query_rerank_property_required);
            }
            return null;
        }
        return new WeaviateRerankSpec(property, query);
    }

    /**
     * Show the target-vector section only where there is a choice to make: a collection declaring
     * fewer than two vectors has nothing to target, and an empty picker on every ordinary
     * collection is noise. Same exclude-and-relayout idiom as the tenant row.
     */
    private void refreshTargetSections() {
        boolean visible = currentVectorizers().size() >= 2;
        targetsAvailable = visible;
        for (Map.Entry<WeaviateQueryMode, Composite> entry : targetGroups.entrySet()) {
            Composite group = entry.getValue();
            if (group == null || group.isDisposed()) {
                continue;
            }
            // The twistie and title are the section itself, one level up from the client we fill.
            Composite section = group.getParent();
            section.setVisible(visible);
            ((GridData) section.getLayoutData()).exclude = !visible;
            if (!visible) {
                clearTargetRows(entry.getKey());
            } else {
                // The collection may have changed under the panel, so what is on offer -- and
                // whether there is anything left to add -- has to be recomputed.
                syncTargetChoices(entry.getKey());
            }
        }
        // Near Vector's single unnamed vector box only makes sense while the server can guess
        // which space to search it in. Once targets are on offer every vector belongs to one of
        // them, so the lone box would be a second, contradictory way to say the same thing.
        setNearVectorRowVisible(!visible);
        reflow();
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

    /**
     * The collection's named vectors, in the order the model sorts them. Empty when the panel is
     * not bound to a Weaviate collection, or the schema cannot be read.
     */
    @NotNull
    private List<WeaviateVectorizer> currentVectorizers() {
        WeaviateCollection collection = currentCollection();
        if (collection == null) {
            return Collections.emptyList();
        }
        try {
            return collection.getVectorizers(new VoidProgressMonitor());
        } catch (Exception e) {
            log.debug("Failed to load Weaviate named vectors for the target picker", e);
            return Collections.emptyList();
        }
    }

    private void addTargetRow(@NotNull WeaviateQueryMode mode, @Nullable WeaviateVectorTarget seed) {
        Composite holder = targetRowsHolders.get(mode);
        if (holder == null || holder.isDisposed()) {
            return;
        }
        TargetRowUi row = new TargetRowUi(
            holder, mode, currentVectorizers(), freeTargetNames(mode), seed);
        targetRowUis.get(mode).add(row);
        syncTargetWeightsEnabled(mode);
        syncTargetChoices(mode);
        relayoutTargets(mode);
    }

    private void clearTargetRows(@NotNull WeaviateQueryMode mode) {
        List<TargetRowUi> rows = targetRowUis.get(mode);
        if (rows == null) {
            return;
        }
        for (TargetRowUi row : new ArrayList<>(rows)) {
            row.dispose();
        }
        rows.clear();
        syncTargetChoices(mode);
        // As with the filters: loading a spec that names no targets clears the rows without adding
        // any, and the title would otherwise still advertise the previous spec's count.
        updateTargetCount(mode);
    }

    private void relayoutTargets(@NotNull WeaviateQueryMode mode) {
        Composite holder = targetRowsHolders.get(mode);
        if (holder != null && !holder.isDisposed()) {
            holder.layout(true, true);
        }
        // Reflows as part of retitling, so there is no separate reflow to make here.
        updateTargetCount(mode);
    }

    /**
     * Grey out every weight cell unless the chosen join strategy actually uses one, so a number
     * typed there cannot look as though it is doing something it is not.
     */
    private void syncTargetWeightsEnabled(@NotNull WeaviateQueryMode mode) {
        boolean weighted = currentCombination(mode).usesWeights();
        List<TargetRowUi> rows = targetRowUis.get(mode);
        if (rows == null) {
            return;
        }
        for (TargetRowUi row : rows) {
            row.setWeightEnabled(weighted);
        }
    }

    @NotNull
    private WeaviateVectorCombination currentCombination(@NotNull WeaviateQueryMode mode) {
        Combo combo = targetJoinCombos.get(mode);
        int idx = combo == null || combo.isDisposed() ? 0 : combo.getSelectionIndex();
        if (idx < 0) idx = 0;
        return WeaviateVectorCombination.values()[idx];
    }

    /**
     * The targets the current mode's rows describe, validated as a set.
     *
     * @throws IllegalArgumentException with a message for the banner, so a query that cannot
     *                                  succeed is never sent
     */
    @NotNull
    private List<WeaviateVectorTarget> currentTargets(@NotNull WeaviateQueryMode mode) {
        // Deliberately not gated on the section being visible: a collapsed or off-screen section
        // still holds real choices, and an incidental false here would drop the targets and quietly
        // run a different query than the one on screen. refreshTargetSections empties the rows when
        // it hides the section, so an unusable section has nothing to collect anyway.
        List<TargetRowUi> rows = targetRowUis.get(mode);
        if (rows == null || rows.isEmpty()) {
            requireTargetWhereAmbiguous(mode, 0);
            return Collections.emptyList();
        }
        List<WeaviateVectorTarget> targets = new ArrayList<>(rows.size());
        List<String> seen = new ArrayList<>(rows.size());
        for (TargetRowUi row : rows) {
            WeaviateVectorTarget target = row.toTarget(mode);
            if (target == null) {
                continue;
            }
            if (seen.contains(target.getName())) {
                throw new IllegalArgumentException(
                    NLS.bind(WeaviateUIMessages.query_target_duplicate, target.getName()));
            }
            seen.add(target.getName());
            targets.add(target);
        }
        // A weight only matters once there is something to weigh it against, and a strategy that
        // uses weights needs one on every target -- weighing some and not others is not a thing
        // the server can do anything sensible with.
        WeaviateVectorCombination combination = currentCombination(mode);
        if (targets.size() > 1 && combination.usesWeights()) {
            for (WeaviateVectorTarget target : targets) {
                if (target.getWeight() == null) {
                    throw new IllegalArgumentException(
                        NLS.bind(WeaviateUIMessages.query_target_weight_required,
                            new Object[]{combination.getLabel(), target.getName()}));
                }
            }
        }
        requireTargetWhereAmbiguous(mode, targets.size());
        return targets;
    }

    /**
     * Refuse an untargeted search on a collection with several named vectors.
     * <p>
     * Weaviate cannot guess which space to search and rejects the query, but says so in terms of
     * its own wire format. Nothing is preselected on the user's behalf -- picking a target for
     * them would return plausible but wrong rows, the same reason the tenant combo starts empty --
     * so the panel names the missing choice instead.
     */
    private void requireTargetWhereAmbiguous(@NotNull WeaviateQueryMode mode, int chosen) {
        if (chosen == 0 && targetsAvailable && mode.supportsTargetVectors()) {
            throw new IllegalArgumentException(
                NLS.bind(WeaviateUIMessages.query_target_required, mode.getLabel()));
        }
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
        // Last of the stacked sections and collapsed by default: a query is usually run without
        // filters, and the rows below are the panel's tallest block when they are used.
        filtersGroup = createSection(parent, WeaviateUIMessages.query_filters, "filters", 1, false);

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
        FilterRowUi ui = new FilterRowUi(filterRowsHolder, filterPropertyNames(), seed);
        filterRowUis.add(ui);
        filterRowsHolder.layout(true, true);
        updateFilterCount();
    }

    private void clearFilterRows() {
        for (FilterRowUi ui : new ArrayList<>(filterRowUis)) {
            ui.dispose();
        }
        filterRowUis.clear();
        // Needed on its own account: loading a spec with no filters at all clears the rows without
        // adding any, and the title would otherwise keep the previous spec's count.
        updateFilterCount();
    }

    private List<WeaviateFilterRow> collectFilterRows() {
        List<WeaviateFilterRow> rows = new ArrayList<>(filterRowUis.size());
        for (FilterRowUi ui : filterRowUis) {
            WeaviateFilterRow row = ui.toRow();
            if (row != null) rows.add(row);
        }
        return rows;
    }

    /**
     * Property names for the filter rows: the collection's own, plus the synthetic columns the
     * grid shows.
     * <p>
     * Kept apart from {@link #currentPropertyNames()}, which feeds the BM25, generative and
     * rerank pickers -- none of those can do anything with a uuid or a timestamp. The translator
     * has always known how to filter these three; until now nothing offered them.
     */
    @NotNull
    private List<String> filterPropertyNames() {
        List<String> names = new ArrayList<>();
        names.add(WeaviateFilterTranslator.UUID_COLUMN);
        names.addAll(currentPropertyNames());
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
        // All modes, not just the spec's: these options are per-mode widgets over one shared
        // value each, and a demoted spec must leave them configured everywhere.
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            Spinner spinner = autoCutSpinners.get(mode);
            if (spinner != null && !spinner.isDisposed()) {
                spinner.setSelection(spec.getAutoCut() == null ? 0 : spec.getAutoCut());
            }
            setCheck(explainScoreChecks.get(mode), spec.isExplainScore());
            setCheck(includeVectorChecks.get(mode), spec.isIncludeVector());
            setCheck(createdChecks.get(mode), spec.isWithCreated());
            setCheck(updatedChecks.get(mode), spec.isWithUpdated());
            setCheck(certaintyChecks.get(mode), spec.isWithCertainty());
        }
    }

    private static void setCheck(@Nullable Button check, boolean selected) {
        if (check != null && !check.isDisposed()) {
            check.setSelection(selected);
        }
    }

    /**
     * Rebuild the target rows from a spec, the same way the filter rows are rebuilt from it.
     */
    private void loadTargetsIntoUi(@NotNull WeaviateQuerySpec spec) {
        for (WeaviateQueryMode mode : targetRowsHolders.keySet()) {
            clearTargetRows(mode);
            Combo joinCombo = targetJoinCombos.get(mode);
            if (joinCombo != null && !joinCombo.isDisposed()) {
                WeaviateVectorCombination combination = spec.getCombination();
                joinCombo.select(combination == null ? 0 : combination.ordinal());
            }
            for (WeaviateVectorTarget target : spec.getTargets()) {
                addTargetRow(mode, target);
            }
            syncTargetWeightsEnabled(mode);
        }
    }

    private void loadRerankIntoUi(@NotNull WeaviateQuerySpec spec) {
        WeaviateRerankSpec rerank = spec.getRerank();
        for (WeaviateQueryMode mode : rerankPropertyCombos.keySet()) {
            Combo combo = rerankPropertyCombos.get(mode);
            Text queryField = rerankQueryFields.get(mode);
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
            updateRerankCount(mode);
        }
    }

    /**
     * Show the last grouped-task output in the current mode's result box. One text for the whole
     * result set, so it has no row to live on -- the section is where the task was written, and
     * where its answer is read.
     */
    private void refreshGenerativeResult() {
        WeaviateCollection collection = currentCollection();
        String text = collection == null ? null : collection.getLastGenerativeGroupedResult();
        Text resultField = generativeResultFields.get(currentMode());
        if (resultField != null && !resultField.isDisposed()) {
            resultField.setText(text == null ? "" : text);
        }
    }

    private void loadGenerativeIntoUi(@NotNull WeaviateQuerySpec spec) {
        WeaviateGenerativeTask task = spec.getGenerative();
        for (WeaviateQueryMode mode : generativeSingleFields.keySet()) {
            loadGenerativeIntoUi(mode, task);
        }
    }

    private void loadGenerativeIntoUi(@NotNull WeaviateQueryMode mode, @Nullable WeaviateGenerativeTask task) {
        Text singleField = generativeSingleFields.get(mode);
        if (singleField == null || singleField.isDisposed()) {
            return;
        }
        singleField.setText(task == null || task.getSinglePrompt() == null ? "" : task.getSinglePrompt());
        Text groupedField = generativeGroupedFields.get(mode);
        if (groupedField != null && !groupedField.isDisposed()) {
            groupedField.setText(task == null || task.getGroupedTask() == null ? "" : task.getGroupedTask());
        }
        org.eclipse.swt.widgets.List propertyList = generativePropertyLists.get(mode);
        if (propertyList != null && !propertyList.isDisposed()) {
            propertyList.deselectAll();
            if (task != null) {
                for (String name : task.getGroupedProperties()) {
                    int idx = propertyList.indexOf(name);
                    if (idx >= 0) propertyList.select(idx);
                }
            }
        }
        Combo providerCombo = generativeProviderCombos.get(mode);
        if (providerCombo != null && !providerCombo.isDisposed()) {
            WeaviateGenerativeProvider provider = task == null ? null : task.getProvider();
            int idx = task == null ? 0 : provider == null ? 1 : provider.ordinal() + 2;
            providerCombo.select(idx);
        }
        setFieldText(generativeModelFields.get(mode), task == null ? null : task.getModel());
        setFieldText(generativeTemperatureFields.get(mode),
            task == null || task.getTemperature() == null ? null : task.getTemperature().toString());
        setFieldText(generativeMaxTokensFields.get(mode),
            task == null || task.getMaxTokens() == null ? null : task.getMaxTokens().toString());
        setCheck(generativeMetadataChecks.get(mode), task != null && task.isReturnMetadata());
        syncGenerativeVisibility(mode);
        updateGenerativeCount(mode);
    }

    private static void setFieldText(@Nullable Text field, @Nullable String value) {
        if (field != null && !field.isDisposed()) {
            field.setText(value == null ? "" : value);
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
        loadTargetsIntoUi(spec);
        loadRerankIntoUi(spec);
        loadGenerativeIntoUi(spec);


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
        collection.armRun();
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
        collection.armRun();
        collection.clearLastQueryError();
        modeCombo.select(WeaviateQueryMode.FETCH.ordinal());
        updateFieldVisibility();
        showInfo(WeaviateUIMessages.query_running);
        presentation.getController().refreshData(() -> Display.getDefault().asyncExec(this::refreshStatusFromCollection));
    }

    private WeaviateQuerySpec buildSpec() {
        WeaviateQueryMode mode = currentMode();
        // Collected before the switch: Near Vector reads them to decide whether its own vector
        // box is required at all.
        List<WeaviateVectorTarget> targets = mode.supportsTargetVectors()
            ? currentTargets(mode)
            : Collections.emptyList();
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
            .tenant(currentTenant())
            .autoCut(currentAutoCut())
            .explainScore(currentExplainScore())
            .includeVector(currentIncludeVector())
            .rerank(currentRerank(mode))
            .generative(currentGenerative(mode))
            .withCreated(currentCheck(createdChecks))
            .withUpdated(currentCheck(updatedChecks))
            .withCertainty(currentCheck(certaintyChecks))
            .targets(targets)
            // Only sent when there is more than one target to join; with one there is nothing
            // to join and the model leaves the strategy off the request entirely.
            .combination(targets.size() > 1 ? currentCombination(mode) : null)
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
        refreshGenerativeResult();
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
        refreshTargetSections();
        refreshRerankSections();
        refreshGenerativeSections();
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
        refreshTargetSections();
        refreshRerankSections();
        refreshGenerativeSections();
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
                    updateFilterCount();
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

    /**
     * SWT controls for a single target-vector row. Owns its container Composite, so disposing it
     * removes the row from the layout -- same shape as {@link FilterRowUi}.
     */
    private final class TargetRowUi {
        private final Composite container;
        private final Combo nameCombo;
        private final Text vectorField;
        private final Text weightField;
        private final List<WeaviateVectorizer> vectorizers;

        TargetRowUi(
            @NotNull Composite parent,
            @NotNull WeaviateQueryMode mode,
            @NotNull List<WeaviateVectorizer> vectorizers,
            @NotNull List<String> freeNames,
            @Nullable WeaviateVectorTarget seed
        ) {
            this.vectorizers = vectorizers;
            // Near Vector is the only mode that carries a query vector per target: Near Text and
            // Hybrid hand the server text and it embeds against each target itself.
            boolean takesVector = mode == WeaviateQueryMode.NEAR_VECTOR;

            container = new Composite(parent, SWT.NONE);
            GridLayout gl = new GridLayout(takesVector ? 4 : 3, false);
            gl.marginWidth = 0;
            gl.marginHeight = 0;
            container.setLayout(gl);
            container.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            nameCombo = new Combo(container, SWT.READ_ONLY);
            for (String free : freeNames) {
                nameCombo.add(free);
            }
            GridData ncGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
            ncGd.widthHint = 140;
            nameCombo.setLayoutData(ncGd);
            nameCombo.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    syncVectorHint();
                    // This row just claimed a name and gave up another; both moves change what
                    // the other rows may offer.
                    syncTargetChoices(mode);
                }
            });

            if (takesVector) {
                vectorField = new Text(container, SWT.BORDER);
                vectorField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            } else {
                vectorField = null;
            }

            weightField = new Text(container, SWT.BORDER);
            GridData wGd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
            wGd.widthHint = 60;
            weightField.setLayoutData(wGd);
            weightField.setMessage(WeaviateUIMessages.query_target_weight_hint);
            runOnEnter(weightField);

            Button remove = new Button(container, SWT.PUSH | SWT.FLAT);
            remove.setText("✕");
            remove.setToolTipText(WeaviateUIMessages.query_target_remove);
            remove.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    dispose();
                    targetRowUis.get(mode).remove(TargetRowUi.this);
                    // The name this row held is free again, so it returns to the other dropdowns.
                    syncTargetChoices(mode);
                    relayoutTargets(mode);
                }
            });

            if (seed != null) {
                // A seeded name is this row's own, so it belongs in its list even though the
                // caller counted it as taken.
                if (nameCombo.indexOf(seed.getName()) < 0) {
                    nameCombo.add(seed.getName());
                }
                nameCombo.setText(seed.getName());
                if (seed.getWeight() != null) weightField.setText(seed.getWeight().toString());
                if (vectorField != null) {
                    if (seed.isMulti()) {
                        vectorField.setText(WeaviateVectorParser.formatMulti(seed.getMultiVector()));
                    } else if (seed.getVector() != null) {
                        vectorField.setText(WeaviateVectorParser.format(seed.getVector()));
                    }
                }
            } else if (nameCombo.getItemCount() > 0) {
                nameCombo.select(0);
            }
            syncVectorHint();
        }

        /**
         * A multi-vector index stores a matrix per object and has to be queried with one, so the
         * placeholder follows whichever target is selected rather than showing one shape for both.
         */
        private void syncVectorHint() {
            if (vectorField == null || vectorField.isDisposed()) {
                return;
            }
            vectorField.setMessage(isMultiVectorTarget()
                ? WeaviateUIMessages.query_target_multi_vector_hint
                : WeaviateUIMessages.query_target_vector_hint);
        }

        private boolean isMultiVectorTarget() {
            String name = nameCombo.getText();
            for (WeaviateVectorizer vectorizer : vectorizers) {
                if (vectorizer.getVectorName().equals(name)) {
                    return vectorizer.isMultiVector();
                }
            }
            return false;
        }

        @NotNull
        String selectedName() {
            String name = nameCombo.isDisposed() ? null : nameCombo.getText();
            return name == null ? "" : name;
        }

        /**
         * Re-offer only the vectors still free, keeping this row's own choice in the list.
         * <p>
         * Rebuilt only when the options actually differ: setItems clears the selection, and doing
         * that on every keystroke elsewhere in the panel would make the combo flicker.
         */
        void refreshChoices(@NotNull List<String> taken) {
            if (nameCombo.isDisposed()) {
                return;
            }
            String mine = selectedName();
            List<String> available = new ArrayList<>();
            for (WeaviateVectorizer vectorizer : vectorizers) {
                String name = vectorizer.getVectorName();
                if (name.equals(mine) || !taken.contains(name)) {
                    available.add(name);
                }
            }
            // A name the collection no longer declares -- a spec saved before the schema changed
            // -- stays on the list. Dropping it would silently turn a target the user configured
            // into a blank row that is quietly ignored; left in place they can see it and change
            // it, and running it says plainly that the server does not know that vector.
            if (!mine.isBlank() && !available.contains(mine)) {
                available.add(mine);
            }
            if (available.equals(List.of(nameCombo.getItems()))) {
                return;
            }
            nameCombo.setItems(available.toArray(new String[0]));
            if (!mine.isBlank()) {
                nameCombo.setText(mine);
            }
        }

        void setWeightEnabled(boolean enabled) {
            if (!weightField.isDisposed()) {
                weightField.setEnabled(enabled);
            }
        }

        /**
         * This row as a target, or null when no vector is selected -- an empty row is treated as
         * not filled in yet rather than as an error, matching {@link FilterRowUi#toRow()}.
         *
         * @throws IllegalArgumentException when the row names a target but describes it wrongly
         */
        @Nullable
        WeaviateVectorTarget toTarget(@NotNull WeaviateQueryMode mode) {
            String name = nameCombo.getText();
            if (name == null || name.isBlank()) {
                return null;
            }
            Float weight = readWeight(name);
            if (mode != WeaviateQueryMode.NEAR_VECTOR) {
                return WeaviateVectorTarget.of(name, weight);
            }
            String raw = vectorField == null ? "" : vectorField.getText();
            if (raw == null || raw.isBlank()) {
                throw new IllegalArgumentException(
                    NLS.bind(WeaviateUIMessages.query_target_vector_required, name));
            }
            boolean multi = isMultiVectorTarget();
            // Only the flat direction is pre-checked. A matrix handed to a single-vector target
            // would otherwise fail deep in the flat parser on a stray bracket, whereas the other
            // way round parseMulti already says plainly that it wanted a bracketed vector -- and
            // a pre-check there would have to re-derive what counts as a matrix, which is exactly
            // the judgement the parser is for.
            if (!multi && looksLikeMatrix(raw)) {
                throw new IllegalArgumentException(
                    NLS.bind(WeaviateUIMessages.query_target_expects_flat, name));
            }
            try {
                if (multi) {
                    float[][] parsed = WeaviateVectorParser.parseMulti(raw);
                    if (parsed == null || parsed.length == 0) {
                        throw new IllegalArgumentException(
                            NLS.bind(WeaviateUIMessages.query_target_vector_required, name));
                    }
                    return WeaviateVectorTarget.of(name, weight, parsed);
                }
                float[] parsed = WeaviateVectorParser.parse(raw);
                if (parsed == null || parsed.length == 0) {
                    throw new IllegalArgumentException(
                        NLS.bind(WeaviateUIMessages.query_target_vector_required, name));
                }
                return WeaviateVectorTarget.of(name, weight, parsed);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    NLS.bind(WeaviateUIMessages.query_target_vector_invalid,
                        new Object[]{name, e.getMessage()}));
            }
        }

        @Nullable
        private Float readWeight(@NotNull String name) {
            String raw = weightField.getText();
            if (raw == null || raw.isBlank()) {
                // Whether a missing weight is an error depends on how many targets there are in
                // total, which only currentTargets can see -- it makes that call.
                return null;
            }
            try {
                return Float.parseFloat(raw.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    NLS.bind(WeaviateUIMessages.query_target_weight_invalid,
                        new Object[]{name, raw.strip()}));
            }
        }

        /**
         * Whether this reads as a matrix rather than one flat vector: a second bracket somewhere
         * past the optional outer one. Testing for a literal "[[" would miss "[1, 2], [3, 4]",
         * whose outer brackets parseMulti treats as optional.
         */
        private boolean looksLikeMatrix(@NotNull String raw) {
            String trimmed = raw.strip();
            return trimmed.indexOf('[', trimmed.startsWith("[") ? 1 : 0) >= 0;
        }

        void dispose() {
            if (!container.isDisposed()) container.dispose();
        }
    }

}
