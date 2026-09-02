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
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
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
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateVectorCombination;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateVectorParser;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateVectorTarget;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateVectorizer;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Target vectors section: which named vectors a search runs against, and how their results
 * are joined.
 * <p>
 * Shown only where there is a choice to make. A collection declaring fewer than two vectors has
 * nothing to target, and an empty picker on every ordinary collection is noise -- so the section
 * excludes itself from the layout entirely rather than appearing empty.
 * <p>
 * A vector can only be targeted once. Naming it twice is not a heavier weighting, it is a
 * malformed query, so each row's dropdown offers only the vectors no other row has taken plus its
 * own, and the Add button switches off once every vector is spoken for.
 * <p>
 * When targets become available the panel hides Near Vector's lone vector box -- see
 * {@link WeaviateQueryPanelContext#onTargetsAvailable}. That box is how a single-vector collection
 * says which space to search; once targets are on offer every vector belongs to one of them, and
 * the lone box would be a second, contradictory way to say the same thing.
 */
public class WeaviateTargetSection {

    private static final Log log = Log.getLog(WeaviateTargetSection.class);

    private final WeaviateQueryPanelContext context;

    private final Map<WeaviateQueryMode, Composite> targetGroups = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Combo> targetJoinCombos = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Composite> targetRowsHolders = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, Button> targetAddButtons = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, List<TargetRowUi>> targetRowUis = new EnumMap<>(WeaviateQueryMode.class);

    /** Whether the bound collection declares enough vectors for targeting to mean anything. */
    private boolean targetsAvailable;

    public WeaviateTargetSection(@NotNull WeaviateQueryPanelContext context) {
        this.context = context;
    }

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

    /**
     * Per mode, because the target section is: each mode owns its own rows, so each carries its
     * own count. It matters more here than on the filters -- a search of a collection with several
     * named vectors has to name a target, so "(0)" folded away is a query that will not run.
     */
    private void updateTargetCount(@NotNull WeaviateQueryMode mode) {
        List<TargetRowUi> rows = targetRowUis.get(mode);
        context.setSectionCount(targetGroups.get(mode), WeaviateUIMessages.query_targets,
            rows == null ? 0 : rows.size());
    }

    /**
     * Adds the target-vector section to a mode's own field panel.
     * <p>
     * Per mode like the other options, and only for the modes that can carry a target at all --
     * see {@link WeaviateQueryMode#supportsTargetVectors()}. The section spans both columns of the
     * mode panel, because a row needs more width than a value cell.
     */
    public void addTo(@NotNull Composite c, @NotNull WeaviateQueryMode mode) {
        // Expanded by default: on a collection with several named vectors a search has to name a
        // target, so this is a choice to make rather than an extra to go looking for.
        Composite group = context.createSection(
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
     * Show the target-vector section only where there is a choice to make: a collection declaring
     * fewer than two vectors has nothing to target, and an empty picker on every ordinary
     * collection is noise. Same exclude-and-relayout idiom as the tenant row.
     */
    public void refresh() {
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
        context.onTargetsAvailable(visible);
        context.reflow();
    }

    /**
     * The collection's named vectors, in the order the model sorts them. Empty when the panel is
     * not bound to a Weaviate collection, or the schema cannot be read.
     */
    @NotNull
    private List<WeaviateVectorizer> currentVectorizers() {
        WeaviateCollection collection = context.currentCollection();
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
    public WeaviateVectorCombination currentCombination(@NotNull WeaviateQueryMode mode) {
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
    public List<WeaviateVectorTarget> currentTargets(@NotNull WeaviateQueryMode mode) {
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
     * Rebuild the target rows from a spec, the same way the filter rows are rebuilt from it.
     */
    public void loadFrom(@NotNull WeaviateQuerySpec spec) {
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
            weightField.addListener(SWT.DefaultSelection, e -> context.runQuery());

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
