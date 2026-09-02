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
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBoostCurve;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBoostKind;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBoostModifier;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBoostSpec;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQuerySpec;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;

import java.util.ArrayList;
import java.util.List;

/**
 * The Boost section: nudge the ranking after the search has scored it.
 * <p>
 * Shared across modes, because the client puts {@code boost} on {@code BaseQueryOptions} rather
 * than on any one operator -- a plain fetch can be boosted as readily as a hybrid search.
 * <p>
 * Three of the client's five kinds are offered. {@code filter} would need a whole filter builder
 * nested inside the boost and {@code blend} a list of boosts to combine; both want a different
 * control than a row of inputs, so they are left for their own change rather than half-built here.
 */
public class WeaviateBoostSection {

    /**
     * Weight is a blend fraction in [0, 1], so the midpoint is the only defensible starting point:
     * 0 would make switching a boost on do nothing, and 1 would let it bury the relevance score.
     */
    private static final int DEFAULT_WEIGHT = 50;

    private final WeaviateQueryPanelContext context;

    private Composite group;
    private Combo kindCombo;
    private Combo propertyCombo;
    private Combo modifierCombo;
    private Text originField;
    private Text scaleField;
    private Text offsetField;
    private Combo curveCombo;
    private Spinner weightSpinner;
    private Spinner depthSpinner;

    /** Everything below the kind picker, hidden while no boost is chosen. */
    private final List<Control> whenBoosting = new ArrayList<>();
    /** The modifier, which only the property-value kind reads. */
    private final List<Control> whenPropertyValue = new ArrayList<>();
    /** Origin, scale, offset and curve, which only the two decay kinds read. */
    private final List<Control> whenDecay = new ArrayList<>();

    public WeaviateBoostSection(@NotNull WeaviateQueryPanelContext context) {
        this.context = context;
    }

    public void createControls(@NotNull Composite parent) {
        group = context.createSection(parent, WeaviateUIMessages.query_boost, "boost", 2, false);
        group.setToolTipText(WeaviateUIMessages.query_boost_tip);

        new Label(group, SWT.NONE).setText(WeaviateUIMessages.query_boost_kind);
        kindCombo = new Combo(group, SWT.READ_ONLY);
        kindCombo.add(WeaviateUIMessages.query_boost_none);
        for (WeaviateBoostKind kind : WeaviateBoostKind.values()) {
            kindCombo.add(kind.getLabel());
        }
        kindCombo.select(0);
        kindCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        kindCombo.addSelectionListener(onChange());

        Label propertyLabel = new Label(group, SWT.NONE);
        propertyLabel.setText(WeaviateUIMessages.query_boost_property);
        propertyCombo = new Combo(group, SWT.DROP_DOWN);
        propertyCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        propertyCombo.setToolTipText(WeaviateUIMessages.query_boost_property_tip);
        propertyCombo.addModifyListener(e -> updateCount());
        whenBoosting.add(propertyLabel);
        whenBoosting.add(propertyCombo);

        Label modifierLabel = new Label(group, SWT.NONE);
        modifierLabel.setText(WeaviateUIMessages.query_boost_modifier);
        modifierCombo = new Combo(group, SWT.READ_ONLY);
        modifierCombo.add(WeaviateUIMessages.query_server_default);
        for (WeaviateBoostModifier modifier : WeaviateBoostModifier.values()) {
            modifierCombo.add(modifier.getLabel());
        }
        modifierCombo.select(0);
        modifierCombo.setToolTipText(WeaviateUIMessages.query_boost_modifier_tip);
        whenBoosting.add(modifierLabel);
        whenBoosting.add(modifierCombo);
        whenPropertyValue.add(modifierLabel);
        whenPropertyValue.add(modifierCombo);

        originField = addTextRow(WeaviateUIMessages.query_boost_origin,
            WeaviateUIMessages.query_boost_origin_tip, whenDecay);
        scaleField = addTextRow(WeaviateUIMessages.query_boost_scale,
            WeaviateUIMessages.query_boost_scale_tip, whenDecay);
        offsetField = addTextRow(WeaviateUIMessages.query_boost_offset,
            WeaviateUIMessages.query_boost_offset_tip, whenDecay);

        Label curveLabel = new Label(group, SWT.NONE);
        curveLabel.setText(WeaviateUIMessages.query_boost_curve);
        curveCombo = new Combo(group, SWT.READ_ONLY);
        curveCombo.add(WeaviateUIMessages.query_server_default);
        for (WeaviateBoostCurve curve : WeaviateBoostCurve.values()) {
            curveCombo.add(curve.getLabel());
        }
        curveCombo.select(0);
        whenBoosting.add(curveLabel);
        whenBoosting.add(curveCombo);
        whenDecay.add(curveLabel);
        whenDecay.add(curveCombo);

        Label weightLabel = new Label(group, SWT.NONE);
        weightLabel.setText(WeaviateUIMessages.query_boost_weight);
        weightSpinner = new Spinner(group, SWT.BORDER);
        weightSpinner.setDigits(2);
        weightSpinner.setMinimum(0);
        weightSpinner.setMaximum(100);
        weightSpinner.setSelection(DEFAULT_WEIGHT);
        weightSpinner.setToolTipText(WeaviateUIMessages.query_boost_weight_tip);
        whenBoosting.add(weightLabel);
        whenBoosting.add(weightSpinner);

        Label depthLabel = new Label(group, SWT.NONE);
        depthLabel.setText(WeaviateUIMessages.query_boost_depth);
        depthSpinner = new Spinner(group, SWT.BORDER);
        depthSpinner.setMinimum(0);
        depthSpinner.setMaximum(10000);
        depthSpinner.setSelection(0);
        depthSpinner.setToolTipText(WeaviateUIMessages.query_boost_depth_tip);
        whenBoosting.add(depthLabel);
        whenBoosting.add(depthSpinner);

        syncVisibility();
    }

    /** Refill the property picker from the collection, keeping whatever was typed or chosen. */
    public void refreshProperties() {
        if (propertyCombo == null || propertyCombo.isDisposed()) {
            return;
        }
        String previous = propertyCombo.getText();
        propertyCombo.removeAll();
        for (String name : context.currentPropertyNames()) {
            propertyCombo.add(name);
        }
        // Editable on purpose, so a property the panel has not loaded can still be named.
        propertyCombo.setText(previous == null ? "" : previous);
    }

    public void syncVisibility() {
        if (group == null || group.isDisposed()) {
            return;
        }
        WeaviateBoostKind kind = selectedKind();
        WeaviateSectionWidgets.setVisible(whenBoosting, kind != null);
        WeaviateSectionWidgets.setVisible(whenPropertyValue, kind == WeaviateBoostKind.PROPERTY_VALUE);
        WeaviateSectionWidgets.setVisible(whenDecay, kind != null && kind.isDecay());
        group.layout(true, true);
        context.reflow();
        updateCount();
    }

    public void updateCount() {
        context.setSectionCount(group, WeaviateUIMessages.query_boost, selectedKind() == null ? 0 : 1);
    }

    /**
     * The boost as configured, or null for none.
     *
     * @throws IllegalArgumentException from the spec when the combination cannot be sent -- a
     *                                  decay without a scale, a numeric origin that is not a
     *                                  number. Reported in the banner rather than by the server
     */
    @Nullable
    public WeaviateBoostSpec currentBoost() {
        WeaviateBoostKind kind = selectedKind();
        if (kind == null) {
            return null;
        }
        return new WeaviateBoostSpec(
            kind,
            propertyCombo.getText(),
            kind.isDecay() ? textOrNull(originField) : null,
            kind.isDecay() ? textOrNull(scaleField) : null,
            kind.isDecay() ? textOrNull(offsetField) : null,
            kind.isDecay() ? selectedCurve() : null,
            null,
            kind == WeaviateBoostKind.PROPERTY_VALUE ? selectedModifier() : null,
            weightSpinner.getSelection() / 100f,
            depthSpinner.getSelection());
    }

    public void loadFrom(@NotNull WeaviateQuerySpec spec) {
        if (kindCombo == null || kindCombo.isDisposed()) {
            return;
        }
        refreshProperties();
        WeaviateBoostSpec boost = spec.getBoost();
        kindCombo.select(boost == null ? 0 : boost.kind().ordinal() + 1);
        if (boost != null) {
            propertyCombo.setText(boost.property());
            WeaviateSectionWidgets.setText(originField, boost.origin());
            WeaviateSectionWidgets.setText(scaleField, boost.scale());
            WeaviateSectionWidgets.setText(offsetField, boost.offset());
            curveCombo.select(boost.curve() == null ? 0 : boost.curve().ordinal() + 1);
            modifierCombo.select(boost.modifier() == null ? 0 : boost.modifier().ordinal() + 1);
            if (boost.weight() != null) {
                weightSpinner.setSelection(Math.round(boost.weight() * 100));
            }
            depthSpinner.setSelection(boost.depth() == null ? 0 : boost.depth());
        }
        syncVisibility();
    }

    @NotNull
    private Text addTextRow(@NotNull String label, @NotNull String tip, @NotNull List<Control> shownWith) {
        Label rowLabel = new Label(group, SWT.NONE);
        rowLabel.setText(label);
        Text field = new Text(group, SWT.BORDER);
        field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        field.setToolTipText(tip);
        whenBoosting.add(rowLabel);
        whenBoosting.add(field);
        shownWith.add(rowLabel);
        shownWith.add(field);
        return field;
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

    @Nullable
    private static String textOrNull(@Nullable Text field) {
        if (field == null || field.isDisposed()) {
            return null;
        }
        String text = field.getText();
        return text == null || text.isBlank() ? null : text;
    }

    @Nullable
    private WeaviateBoostKind selectedKind() {
        if (kindCombo == null || kindCombo.isDisposed()) {
            return null;
        }
        int index = kindCombo.getSelectionIndex();
        return index <= 0 ? null : WeaviateBoostKind.values()[index - 1];
    }

    @Nullable
    private WeaviateBoostCurve selectedCurve() {
        int index = curveCombo.getSelectionIndex();
        return index <= 0 ? null : WeaviateBoostCurve.values()[index - 1];
    }

    @Nullable
    private WeaviateBoostModifier selectedModifier() {
        int index = modifierCombo.getSelectionIndex();
        return index <= 0 ? null : WeaviateBoostModifier.values()[index - 1];
    }
}
