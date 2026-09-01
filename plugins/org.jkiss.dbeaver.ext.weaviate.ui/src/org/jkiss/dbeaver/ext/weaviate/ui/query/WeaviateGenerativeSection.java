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
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateGenerativeProvider;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateGenerativeTask;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQueryMode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateQuerySpec;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Generative section, added to every mode's field panel.
 * <p>
 * Every mode gets one, unlike Rerank: the generate client mirrors every query operator, a plain
 * fetch included. The section holds two different things -- a single prompt run once per object,
 * and a grouped task run once over the whole result set -- because the server treats them as two
 * separate requests and either, both or neither may be set.
 * <p>
 * The grouped answer has no row to live on, so it comes back to the box in this section rather
 * than into the grid. That is why the section is also where the result is read, not just written.
 */
public class WeaviateGenerativeSection {

    private static final Log log = Log.getLog(WeaviateGenerativeSection.class);

    private final WeaviateQueryPanelContext context;

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
    /** Rows revealed as the provider choice narrows; see {@link #syncVisibility}. */
    private final Map<WeaviateQueryMode, List<Control>> generativeWhenGenerating = new EnumMap<>(WeaviateQueryMode.class);
    private final Map<WeaviateQueryMode, List<Control>> generativeWhenProviderNamed = new EnumMap<>(WeaviateQueryMode.class);

    public WeaviateGenerativeSection(@NotNull WeaviateQueryPanelContext context) {
        this.context = context;
    }

    /**
     * Adds the Generative section to a mode's field panel. Every mode gets one -- the generate
     * client mirrors every query operator, a plain fetch included.
     */
    public void addTo(@NotNull Composite c, @NotNull WeaviateQueryMode mode) {
        Composite group = context.createSection(
            c, WeaviateUIMessages.query_generative, "generative." + mode.name(), 2, false);
        group.setToolTipText(WeaviateUIMessages.query_generative_tip);
        ((GridData) group.getParent().getLayoutData()).horizontalSpan = 2;

        // The provider leads, and doubles as the section's on/off switch: with "(no generation)"
        // selected there is nothing to prompt, so the rest of the section is not merely disabled
        // but absent. Choosing anything reveals it.
        new Label(group, SWT.NONE).setText(WeaviateUIMessages.query_generative_provider);
        Combo providerCombo = new Combo(group, SWT.READ_ONLY);
        providerCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        providerCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                syncVisibility(mode);
                updateCount(mode);
            }
        });

        // Shown whenever generation is on at all.
        List<Control> whenGenerating = new ArrayList<>();
        Label singleLabel = new Label(group, SWT.NONE);
        singleLabel.setText(WeaviateUIMessages.query_generative_single);
        Text singleField = new Text(group, SWT.BORDER);
        singleField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        singleField.setMessage(WeaviateUIMessages.query_generative_single_hint);
        singleField.addListener(SWT.Modify, e -> updateCount(mode));
        whenGenerating.add(singleLabel);
        whenGenerating.add(singleField);

        Label groupedLabel = new Label(group, SWT.NONE);
        groupedLabel.setText(WeaviateUIMessages.query_generative_grouped);
        Text groupedField = new Text(group, SWT.BORDER);
        groupedField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        groupedField.setMessage(WeaviateUIMessages.query_generative_grouped_hint);
        groupedField.addListener(SWT.Modify, e -> updateCount(mode));
        whenGenerating.add(groupedLabel);
        whenGenerating.add(groupedField);

        Label propsLabel = new Label(group, SWT.NONE);
        propsLabel.setText(WeaviateUIMessages.query_generative_properties);
        propsLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
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
        modelField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        whenProviderNamed.add(modelLabel);
        whenProviderNamed.add(modelField);

        Label temperatureLabel = new Label(group, SWT.NONE);
        temperatureLabel.setText(WeaviateUIMessages.query_generative_temperature);
        Text temperatureField = new Text(group, SWT.BORDER);
        temperatureField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        whenProviderNamed.add(temperatureLabel);
        whenProviderNamed.add(temperatureField);

        Label maxTokensLabel = new Label(group, SWT.NONE);
        maxTokensLabel.setText(WeaviateUIMessages.query_generative_max_tokens);
        Text maxTokensField = new Text(group, SWT.BORDER);
        maxTokensField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
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
        resultLabel.setLayoutData(new GridData(SWT.LEFT, SWT.TOP, false, false));
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
        syncVisibility(mode);
    }

    /**
     * Refill the provider dropdowns and property lists from the collection the panel is bound
     * to. The first provider entry is the collection's own generative module, named so the
     * default is a visible choice rather than a blank.
     */
    public void refresh() {
        WeaviateCollection collection = context.currentCollection();
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
        List<String> properties = context.currentPropertyNames();
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
            syncVisibility(mode);
            updateCount(mode);
        }
    }

    /**
     * Reveal as much of the section as the provider choice justifies.
     * <p>
     * Hidden rather than disabled: with no generation chosen there is nothing to prompt, and a
     * column of greyed-out boxes reads as broken rather than as not-applicable. The rows keep
     * their contents while hidden, so flipping the provider back brings the prompts with it.
     */
    public void syncVisibility(@NotNull WeaviateQueryMode mode) {
        int idx = providerIndex(mode);
        WeaviateSectionWidgets.setVisible(generativeWhenGenerating.get(mode), idx > 0);
        WeaviateSectionWidgets.setVisible(generativeWhenProviderNamed.get(mode), idx > 1);
        Composite group = generativeGroups.get(mode);
        if (group != null && !group.isDisposed()) {
            group.layout(true, true);
        }
        context.reflow();
    }

    public int providerIndex(@NotNull WeaviateQueryMode mode) {
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
    public WeaviateGenerativeProvider selectedProvider(@NotNull WeaviateQueryMode mode) {
        int idx = providerIndex(mode);
        return idx <= 1 ? null : WeaviateGenerativeProvider.values()[idx - 2];
    }

    /** Count = prompts filled in (0-2), so the folded title says whether anything will generate. */
    public void updateCount(@NotNull WeaviateQueryMode mode) {
        if (providerIndex(mode) == 0) {
            context.setSectionCount(generativeGroups.get(mode), WeaviateUIMessages.query_generative, 0);
            return;
        }
        int count = 0;
        Text single = generativeSingleFields.get(mode);
        Text grouped = generativeGroupedFields.get(mode);
        if (single != null && !single.isDisposed() && !single.getText().isBlank()) count++;
        if (grouped != null && !grouped.isDisposed() && !grouped.getText().isBlank()) count++;
        context.setSectionCount(generativeGroups.get(mode), WeaviateUIMessages.query_generative, count);
    }

    /**
     * The generative task the current mode's section describes, or null for none.
     *
     * @throws IllegalArgumentException with a banner message for a half-configured task, so a
     *                                  request that cannot mean what it says is never sent
     */
    @Nullable
    public WeaviateGenerativeTask currentGenerative(@NotNull WeaviateQueryMode mode) {
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
        WeaviateGenerativeProvider provider = selectedProvider(mode);
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
     * Show the last grouped-task output in the current mode's result box. One text for the whole
     * result set, so it has no row to live on -- the section is where the task was written, and
     * where its answer is read.
     */
    public void refreshResult() {
        WeaviateCollection collection = context.currentCollection();
        String text = collection == null ? null : collection.getLastGenerativeGroupedResult();
        Text resultField = generativeResultFields.get(context.currentMode());
        if (resultField != null && !resultField.isDisposed()) {
            resultField.setText(text == null ? "" : text);
        }
    }

    public void loadFrom(@NotNull WeaviateQuerySpec spec) {
        WeaviateGenerativeTask task = spec.getGenerative();
        for (WeaviateQueryMode mode : generativeSingleFields.keySet()) {
            loadFrom(mode, task);
        }
    }

    public void loadFrom(@NotNull WeaviateQueryMode mode, @Nullable WeaviateGenerativeTask task) {
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
        WeaviateSectionWidgets.setText(generativeModelFields.get(mode), task == null ? null : task.getModel());
        WeaviateSectionWidgets.setText(generativeTemperatureFields.get(mode),
            task == null || task.getTemperature() == null ? null : task.getTemperature().toString());
        WeaviateSectionWidgets.setText(generativeMaxTokensFields.get(mode),
            task == null || task.getMaxTokens() == null ? null : task.getMaxTokens().toString());
        WeaviateSectionWidgets.setChecked(generativeMetadataChecks.get(mode), task != null && task.isReturnMetadata());
        syncVisibility(mode);
        updateCount(mode);
    }
}
