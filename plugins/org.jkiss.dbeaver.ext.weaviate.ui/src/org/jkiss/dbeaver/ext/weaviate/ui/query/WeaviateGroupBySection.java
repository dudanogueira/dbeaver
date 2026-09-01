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
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateGroupBySpec;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQuerySpec;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;

import java.util.ArrayList;
import java.util.List;

/**
 * The group-by section: bucket the matches by one property.
 * <p>
 * Shared across modes rather than built per mode, the way Filters is. The client has a grouped
 * overload for all six operators, so grouping is not a property of a particular search -- it is
 * something done to whatever search is selected.
 * <p>
 * Index 0 of the property combo is "no grouping", which is what makes the combo the section's
 * on/off switch rather than needing a separate checkbox.
 */
public class WeaviateGroupBySection {

    private final WeaviateQueryPanelContext context;

    private Composite groupByGroup;
    private Combo groupPropertyCombo;
    private Spinner groupMaxGroupsSpinner;
    private Spinner groupObjectsPerGroupSpinner;
    private Button groupStatsCheck;
    private Label groupUnavailableLabel;

    private final List<Control> groupPropertyRow = new ArrayList<>();
    /** Controls revealed only once a property is picked; see {@link #syncVisibility()}. */
    private final List<Control> groupWhenGrouping = new ArrayList<>();

    public WeaviateGroupBySection(@NotNull WeaviateQueryPanelContext context) {
        this.context = context;
    }

    public void createControls(@NotNull Composite parent) {
        groupByGroup = context.createSection(
            parent, WeaviateUIMessages.query_group_by, "groupBy", 2, false);
        groupByGroup.setToolTipText(WeaviateUIMessages.query_group_by_tip);

        Label propertyLabel = new Label(groupByGroup, SWT.NONE);
        propertyLabel.setText(WeaviateUIMessages.query_group_property);
        groupPropertyCombo = new Combo(groupByGroup, SWT.DROP_DOWN | SWT.READ_ONLY);
        groupPropertyCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        groupPropertyCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                syncVisibility();
                updateCount();
            }
        });

        // The three controls below say nothing until there is something to group, so they stay
        // hidden until a property is picked -- the same choice the generative section makes for
        // the same reason. Their values survive being hidden.
        Label groupsLabel = new Label(groupByGroup, SWT.NONE);
        groupsLabel.setText(WeaviateUIMessages.query_group_max_groups);
        groupMaxGroupsSpinner = new Spinner(groupByGroup, SWT.BORDER);
        groupMaxGroupsSpinner.setMinimum(1);
        groupMaxGroupsSpinner.setMaximum(1000);
        groupMaxGroupsSpinner.setSelection(WeaviateGroupBySpec.DEFAULT_MAX_GROUPS);
        groupMaxGroupsSpinner.setToolTipText(WeaviateUIMessages.query_group_max_groups_tip);

        Label perGroupLabel = new Label(groupByGroup, SWT.NONE);
        perGroupLabel.setText(WeaviateUIMessages.query_group_objects_per_group);
        groupObjectsPerGroupSpinner = new Spinner(groupByGroup, SWT.BORDER);
        groupObjectsPerGroupSpinner.setMinimum(1);
        groupObjectsPerGroupSpinner.setMaximum(1000);
        groupObjectsPerGroupSpinner.setSelection(WeaviateGroupBySpec.DEFAULT_MAX_OBJECTS_PER_GROUP);
        groupObjectsPerGroupSpinner.setToolTipText(
            WeaviateUIMessages.query_group_objects_per_group_tip);

        Label statsFiller = new Label(groupByGroup, SWT.NONE);
        groupStatsCheck = new Button(groupByGroup, SWT.CHECK);
        groupStatsCheck.setText(WeaviateUIMessages.query_group_stats);
        groupStatsCheck.setToolTipText(WeaviateUIMessages.query_group_stats_tip);

        // Shown in place of the controls under a mode that cannot group. The section stays put:
        // it opens on Fetch, and a section that vanishes there is a feature nobody finds.
        groupUnavailableLabel = new Label(groupByGroup, SWT.WRAP);
        groupUnavailableLabel.setText(WeaviateUIMessages.query_group_unavailable);
        GridData gu = new GridData(SWT.FILL, SWT.CENTER, true, false);
        gu.horizontalSpan = 2;
        groupUnavailableLabel.setLayoutData(gu);

        groupPropertyRow.clear();
        groupPropertyRow.add(propertyLabel);
        groupPropertyRow.add(groupPropertyCombo);

        groupWhenGrouping.clear();
        groupWhenGrouping.add(groupsLabel);
        groupWhenGrouping.add(groupMaxGroupsSpinner);
        groupWhenGrouping.add(perGroupLabel);
        groupWhenGrouping.add(groupObjectsPerGroupSpinner);
        groupWhenGrouping.add(statsFiller);
        groupWhenGrouping.add(groupStatsCheck);

        refreshProperties();
        syncVisibility();
    }

    /**
     * Refill the property dropdown from the collection, keeping the current choice if it is still
     * a property.
     */
    public void refreshProperties() {
        if (groupPropertyCombo == null || groupPropertyCombo.isDisposed()) {
            return;
        }
        String previous = selectedProperty();
        groupPropertyCombo.removeAll();
        groupPropertyCombo.add(WeaviateUIMessages.query_group_none);
        for (String name : context.currentPropertyNames()) {
            groupPropertyCombo.add(name);
        }
        int index = previous == null ? 0 : groupPropertyCombo.indexOf(previous);
        groupPropertyCombo.select(Math.max(index, 0));
    }

    /**
     * Reveal the group-by controls only where they can be used, and only once a property is
     * picked.
     * <p>
     * Under a plain fetch the server refuses a grouped query outright, so the controls go and a
     * line saying why takes their place. The section itself stays: it is the mode the panel opens
     * on, and a section that disappears there is a feature nobody discovers -- which is exactly
     * what the first cut of this did. The chosen property survives the trip through fetch and
     * comes back when a ranked mode is selected.
     */
    public void syncVisibility() {
        boolean applies = context.currentMode().supportsGroupBy();
        WeaviateSectionRows.setVisible(groupWhenGrouping, applies && selectedProperty() != null);
        WeaviateSectionRows.setVisible(groupPropertyRow, applies);
        WeaviateSectionRows.setVisible(List.of(groupUnavailableLabel), !applies);
        if (groupByGroup != null && !groupByGroup.isDisposed()) {
            groupByGroup.layout(true, true);
        }
        context.reflow();
    }

    public void updateCount() {
        // 1 or 0 rather than a row count: there is only ever one grouping. The number in the
        // title is really an "on" light, and setSectionCount already drops a zero.
        context.setSectionCount(groupByGroup, WeaviateUIMessages.query_group_by,
            selectedProperty() == null ? 0 : 1);
    }

    /** The grouping as the spec wants it, or null when none is chosen. */
    @Nullable
    public WeaviateGroupBySpec currentGroupBy() {
        String property = selectedProperty();
        if (property == null) {
            return null;
        }
        // Kept in the spec even under a mode that cannot group, so switching back to a ranked
        // mode restores it. The spec's own isGrouped() decides whether it is sent.
        return new WeaviateGroupBySpec(
            property,
            groupMaxGroupsSpinner == null || groupMaxGroupsSpinner.isDisposed()
                ? WeaviateGroupBySpec.DEFAULT_MAX_GROUPS
                : groupMaxGroupsSpinner.getSelection(),
            groupObjectsPerGroupSpinner == null || groupObjectsPerGroupSpinner.isDisposed()
                ? WeaviateGroupBySpec.DEFAULT_MAX_OBJECTS_PER_GROUP
                : groupObjectsPerGroupSpinner.getSelection(),
            groupStatsCheck != null && !groupStatsCheck.isDisposed()
                && groupStatsCheck.getSelection());
    }

    public void loadFrom(@NotNull WeaviateQuerySpec spec) {
        if (groupPropertyCombo == null || groupPropertyCombo.isDisposed()) {
            return;
        }
        refreshProperties();
        WeaviateGroupBySpec groupBy = spec.getGroupBy();
        if (groupBy == null) {
            groupPropertyCombo.select(0);
        } else {
            int index = groupPropertyCombo.indexOf(groupBy.getProperty());
            // A property the collection no longer declares falls back to no grouping rather than
            // to whatever sits at that index.
            groupPropertyCombo.select(Math.max(index, 0));
            groupMaxGroupsSpinner.setSelection(groupBy.getMaxGroups());
            groupObjectsPerGroupSpinner.setSelection(groupBy.getMaxObjectsPerGroup());
            groupStatsCheck.setSelection(groupBy.isWithGroupStats());
        }
        syncVisibility();
        updateCount();
    }

    @Nullable
    private String selectedProperty() {
        if (groupPropertyCombo == null || groupPropertyCombo.isDisposed()) {
            return null;
        }
        int index = groupPropertyCombo.getSelectionIndex();
        return index <= 0 ? null : groupPropertyCombo.getItem(index);
    }
}
