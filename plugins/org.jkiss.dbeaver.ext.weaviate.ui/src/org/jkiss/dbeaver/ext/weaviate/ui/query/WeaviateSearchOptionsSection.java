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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateConsistencyLevel;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDiversitySpec;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQuerySpec;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateSearchOperator;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;

import java.util.ArrayList;
import java.util.List;

/**
 * The Search options section: consistency level, keyword operator, MMR and the query profile.
 * <p>
 * Shared rather than per mode, because each of these is a single value on the spec -- there is one
 * consistency level, not one per search. What differs per mode is only whether a control applies
 * at all, and that is decided by the mode's own capability methods, which in turn mirror the
 * client builders that accept each option:
 * <pre>
 * consistency level   every mode           BaseQueryOptions.Builder
 * query profile       every mode           opt-in metadata
 * search operator     BM25, Hybrid         Bm25/Hybrid.Builder only
 * MMR diversity       Hybrid, near_*       BaseVectorSearchBuilder + Hybrid
 * </pre>
 * A control that does not apply is taken out of the layout rather than disabled, the same choice
 * the group-by section makes: a greyed row invites someone to work out why.
 * <p>
 * Every control has an explicit "leave it to the server" position, and that is the default. Unset
 * is not the same as a default value -- sending no {@code searchOperator} lets the server choose
 * and change its mind between versions, while sending OR with a minimum of one pins it.
 */
public class WeaviateSearchOptionsSection {

    /**
     * MMR needs a limit and the server refuses anything below one, so the checkbox has to start
     * from a real number. Ten is small enough to diversify a default page of rows and large enough
     * that turning it on shows something.
     */
    private static final int DEFAULT_MMR_LIMIT = 10;
    private static final int BALANCE_DIGITS = 2;

    private final WeaviateQueryPanelContext context;

    private Composite group;
    private Combo consistencyCombo;
    private Combo operatorCombo;
    private Spinner minimumSpinner;
    private Button mmrCheck;
    private Spinner mmrLimitSpinner;
    private Spinner mmrBalanceSpinner;
    private Button profileCheck;

    /** Rows that only a keyword search can use. */
    private final List<Control> whenKeyword = new ArrayList<>();
    /** Rows that only a vector-ranked search can use. */
    private final List<Control> whenVector = new ArrayList<>();
    /** The minimum-tokens row, which only the OR operator reads. */
    private final List<Control> whenMinimum = new ArrayList<>();
    /** The MMR settings, hidden until MMR is switched on. */
    private final List<Control> whenDiversifying = new ArrayList<>();

    public WeaviateSearchOptionsSection(@NotNull WeaviateQueryPanelContext context) {
        this.context = context;
    }

    public void createControls(@NotNull Composite parent) {
        group = context.createSection(
            parent, WeaviateUIMessages.query_options, "searchOptions", 2, false);

        // -- consistency level: every mode -----------------------------------------------------
        new Label(group, SWT.NONE).setText(WeaviateUIMessages.query_consistency);
        consistencyCombo = new Combo(group, SWT.READ_ONLY);
        consistencyCombo.add(WeaviateUIMessages.query_server_default);
        for (WeaviateConsistencyLevel level : WeaviateConsistencyLevel.values()) {
            consistencyCombo.add(level.getLabel());
        }
        consistencyCombo.select(0);
        consistencyCombo.setToolTipText(WeaviateUIMessages.query_consistency_tip);
        consistencyCombo.addSelectionListener(onChange());

        // -- keyword operator: BM25 and hybrid --------------------------------------------------
        Label operatorLabel = new Label(group, SWT.NONE);
        operatorLabel.setText(WeaviateUIMessages.query_operator);
        operatorCombo = new Combo(group, SWT.READ_ONLY);
        operatorCombo.add(WeaviateUIMessages.query_server_default);
        for (WeaviateSearchOperator operator : WeaviateSearchOperator.values()) {
            operatorCombo.add(operator.getLabel());
        }
        operatorCombo.select(0);
        operatorCombo.setToolTipText(WeaviateUIMessages.query_operator_tip);
        operatorCombo.addSelectionListener(onChange());
        whenKeyword.add(operatorLabel);
        whenKeyword.add(operatorCombo);

        Label minimumLabel = new Label(group, SWT.NONE);
        minimumLabel.setText(WeaviateUIMessages.query_operator_minimum);
        minimumSpinner = new Spinner(group, SWT.BORDER);
        minimumSpinner.setMinimum(1);
        minimumSpinner.setMaximum(100);
        minimumSpinner.setSelection(1);
        minimumSpinner.setToolTipText(WeaviateUIMessages.query_operator_minimum_tip);
        minimumSpinner.addModifyListener(e -> updateCount());
        whenKeyword.add(minimumLabel);
        whenKeyword.add(minimumSpinner);
        whenMinimum.add(minimumLabel);
        whenMinimum.add(minimumSpinner);

        // -- MMR: hybrid and the near_* modes ---------------------------------------------------
        Label mmrFiller = new Label(group, SWT.NONE);
        mmrCheck = new Button(group, SWT.CHECK);
        mmrCheck.setText(WeaviateUIMessages.query_diversify);
        mmrCheck.setToolTipText(WeaviateUIMessages.query_diversify_tip);
        mmrCheck.addSelectionListener(onChange());
        whenVector.add(mmrFiller);
        whenVector.add(mmrCheck);

        Label mmrLimitLabel = new Label(group, SWT.NONE);
        mmrLimitLabel.setText(WeaviateUIMessages.query_diversify_limit);
        mmrLimitSpinner = new Spinner(group, SWT.BORDER);
        mmrLimitSpinner.setMinimum(WeaviateDiversitySpec.MIN_LIMIT);
        mmrLimitSpinner.setMaximum(10000);
        mmrLimitSpinner.setSelection(DEFAULT_MMR_LIMIT);
        mmrLimitSpinner.setToolTipText(WeaviateUIMessages.query_diversify_limit_tip);
        mmrLimitSpinner.addModifyListener(e -> updateCount());
        whenVector.add(mmrLimitLabel);
        whenVector.add(mmrLimitSpinner);
        whenDiversifying.add(mmrLimitLabel);
        whenDiversifying.add(mmrLimitSpinner);

        Label mmrBalanceLabel = new Label(group, SWT.NONE);
        mmrBalanceLabel.setText(WeaviateUIMessages.query_diversify_balance);
        mmrBalanceSpinner = new Spinner(group, SWT.BORDER);
        mmrBalanceSpinner.setDigits(BALANCE_DIGITS);
        mmrBalanceSpinner.setMinimum(0);
        mmrBalanceSpinner.setMaximum(100);
        mmrBalanceSpinner.setSelection(50);
        mmrBalanceSpinner.setToolTipText(WeaviateUIMessages.query_diversify_balance_tip);
        whenVector.add(mmrBalanceLabel);
        whenVector.add(mmrBalanceSpinner);
        whenDiversifying.add(mmrBalanceLabel);
        whenDiversifying.add(mmrBalanceSpinner);

        // -- query profile: every mode ----------------------------------------------------------
        new Label(group, SWT.NONE);
        profileCheck = new Button(group, SWT.CHECK);
        profileCheck.setText(WeaviateUIMessages.query_profile_enable);
        profileCheck.setToolTipText(WeaviateUIMessages.query_profile_enable_tip);
        profileCheck.addSelectionListener(onChange());

        syncVisibility();
    }

    /**
     * Show each control only where the server would accept it.
     * <p>
     * Called on every mode change, because two of the four options exist for some modes only. The
     * values behind a hidden control survive, so moving through a mode that cannot use MMR and
     * back does not lose the setting -- the spec's own getters drop it while it does not apply.
     */
    public void syncVisibility() {
        if (group == null || group.isDisposed()) {
            return;
        }
        WeaviateSectionWidgets.setVisible(whenKeyword, context.currentMode().supportsSearchOperator());
        WeaviateSectionWidgets.setVisible(whenVector, context.currentMode().supportsDiversity());
        // Within those, two more rows depend on a choice rather than on the mode.
        if (context.currentMode().supportsSearchOperator()) {
            WeaviateSectionWidgets.setVisible(whenMinimum, selectedOperator() == WeaviateSearchOperator.OR);
        }
        if (context.currentMode().supportsDiversity()) {
            WeaviateSectionWidgets.setVisible(whenDiversifying, mmrCheck.getSelection());
        }
        group.layout(true, true);
        context.reflow();
        updateCount();
    }

    /** How many options are set to something other than "leave it to the server". */
    public void updateCount() {
        int count = 0;
        if (selectedConsistency() != null) count++;
        if (selectedOperator() != null && context.currentMode().supportsSearchOperator()) count++;
        if (currentDiversity() != null) count++;
        if (isWithQueryProfile()) count++;
        context.setSectionCount(group, WeaviateUIMessages.query_options, count);
    }

    @Nullable
    public WeaviateConsistencyLevel currentConsistencyLevel() {
        return selectedConsistency();
    }

    @Nullable
    public WeaviateSearchOperator currentSearchOperator() {
        return selectedOperator();
    }

    public int currentMinimumOrTokens() {
        return minimumSpinner == null || minimumSpinner.isDisposed()
            ? 1 : minimumSpinner.getSelection();
    }

    /** The MMR request, or null when it is switched off or the mode cannot diversify. */
    @Nullable
    public WeaviateDiversitySpec currentDiversity() {
        if (mmrCheck == null || mmrCheck.isDisposed() || !mmrCheck.getSelection()
            || !context.currentMode().supportsDiversity()
        ) {
            return null;
        }
        float balance = mmrBalanceSpinner.getSelection() / 100f;
        return new WeaviateDiversitySpec(mmrLimitSpinner.getSelection(), balance);
    }

    public boolean isWithQueryProfile() {
        return profileCheck != null && !profileCheck.isDisposed() && profileCheck.getSelection();
    }

    public void loadFrom(@NotNull WeaviateQuerySpec spec) {
        if (consistencyCombo == null || consistencyCombo.isDisposed()) {
            return;
        }
        WeaviateConsistencyLevel level = spec.getConsistencyLevel();
        consistencyCombo.select(level == null ? 0 : level.ordinal() + 1);

        WeaviateSearchOperator operator = spec.getSearchOperator();
        operatorCombo.select(operator == null ? 0 : operator.ordinal() + 1);
        if (spec.getMinimumOrTokens() != null) {
            minimumSpinner.setSelection(Math.max(1, spec.getMinimumOrTokens()));
        }

        WeaviateDiversitySpec diversity = spec.getDiversity();
        mmrCheck.setSelection(diversity != null);
        if (diversity != null) {
            mmrLimitSpinner.setSelection(diversity.limit());
            if (diversity.balance() != null) {
                mmrBalanceSpinner.setSelection(Math.round(diversity.balance() * 100));
            }
        }
        profileCheck.setSelection(spec.isWithQueryProfile());
        syncVisibility();
    }

    @NotNull
    private SelectionAdapter onChange() {
        return new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                syncVisibility();
            }
        };
    }

    /** Index 0 of each combo is "leave it to the server", which is why these can answer null. */
    @Nullable
    private WeaviateConsistencyLevel selectedConsistency() {
        if (consistencyCombo == null || consistencyCombo.isDisposed()) {
            return null;
        }
        int index = consistencyCombo.getSelectionIndex();
        return index <= 0 ? null : WeaviateConsistencyLevel.values()[index - 1];
    }

    @Nullable
    private WeaviateSearchOperator selectedOperator() {
        if (operatorCombo == null || operatorCombo.isDisposed()) {
            return null;
        }
        int index = operatorCombo.getSelectionIndex();
        return index <= 0 ? null : WeaviateSearchOperator.values()[index - 1];
    }
}
