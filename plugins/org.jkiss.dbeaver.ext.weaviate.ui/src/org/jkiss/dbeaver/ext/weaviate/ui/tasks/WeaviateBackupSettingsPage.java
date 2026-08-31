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
package org.jkiss.dbeaver.ext.weaviate.ui.tasks;

import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateBackupNode;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.tasks.WeaviateBackupSettings;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What to back up, or what to bring back.
 * <p>
 * The two directions share this page because they take the same decisions, and the server takes
 * the same request shape for both. Only the source of the collection list differs: creating offers
 * what the server currently holds, restoring offers what the chosen backup holds.
 * <p>
 * Include and exclude are both offered, as one choice rather than two fields. The server refuses a
 * request carrying values for both -- "'include' and 'exclude' cannot both contain values" -- so
 * making them a mode is the only shape that cannot produce an invalid request.
 */
public class WeaviateBackupSettingsPage extends WizardPage {

    /** The server clamps this to 1-80 and defaults to 50. */
    private static final int CPU_MIN = 1;
    private static final int CPU_MAX = 80;
    private static final int CPU_DEFAULT = 50;

    private final WeaviateBackupTaskWizard wizard;

    private Text idText;
    private Button allCollections;
    private Button includeCollections;
    private Button excludeCollections;
    private Table collectionTable;
    private Text patternText;
    private Label patternHint;
    private Spinner cpuSpinner;

    protected WeaviateBackupSettingsPage(@NotNull WeaviateBackupTaskWizard wizard) {
        super("weaviate.backup.settings");
        this.wizard = wizard;
        setTitle(wizard.isRestore() ? "Restore backup" : "Create new backup");
        setDescription(wizard.isRestore()
            ? "Choose what to bring back from this backup"
            : "Choose what to back up");
    }

    @Override
    public void createControl(Composite parent) {
        WeaviateBackupSettings settings = wizard.getSettings();
        Composite area = UIUtils.createComposite(parent, 1);
        area.setLayoutData(new GridData(GridData.FILL_BOTH));

        Composite header = UIUtils.createComposite(area, 2);
        header.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        UIUtils.createLabel(header, "Backup ID");
        idText = new Text(header, SWT.BORDER);
        idText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        idText.setText(settings.getBackupId());
        if (wizard.isRestore()) {
            // The id names an existing backup; editing it here would be choosing a different one.
            idText.setEditable(false);
        } else {
            // Weaviate accepts ^[a-z0-9_-]+$ and answers 422 for anything else, so the rule is
            // applied while typing rather than reported afterwards.
            idText.addModifyListener(e -> {
                String typed = idText.getText();
                String clean = WeaviateBackupSettings.sanitizeId(typed);
                if (!clean.equals(typed)) {
                    int caret = idText.getCaretPosition();
                    idText.setText(clean);
                    idText.setSelection(Math.min(caret, clean.length()));
                }
                updateState();
            });
        }

        UIUtils.createLabel(header, "Backend");
        UIUtils.createLabel(header, settings.getBackendId());

        Group scope = new Group(area, SWT.NONE);
        scope.setText("Collections");
        scope.setLayout(new GridLayout(1, false));
        scope.setLayoutData(new GridData(GridData.FILL_BOTH));

        SelectionListener onScope = SelectionListener.widgetSelectedAdapter(e -> updateState());
        allCollections = UIUtils.createRadioButton(scope,
            wizard.isRestore() ? "Everything in the backup" : "All collections", Boolean.TRUE, onScope);
        includeCollections = UIUtils.createRadioButton(scope, "Only these", Boolean.FALSE, onScope);
        excludeCollections = UIUtils.createRadioButton(scope, "All except these", Boolean.FALSE, onScope);

        collectionTable = new Table(scope, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL);
        GridData tableGd = new GridData(GridData.FILL_BOTH);
        tableGd.heightHint = 160;
        collectionTable.setLayoutData(tableGd);
        // Ticking a box has to re-validate. Without this the page stays incomplete after the
        // first collection is ticked and only catches up when some other control is touched,
        // which reads as the Next button being broken.
        collectionTable.addSelectionListener(
            SelectionListener.widgetSelectedAdapter(e -> updateState()));
        List<String> known = availableCollections();
        for (String name : known) {
            new TableItem(collectionTable, SWT.NONE).setText(name);
        }
        if (known.isEmpty()) {
            // Restoring a backup reached by a status poll rather than a listing: the status
            // endpoint does not report collections, so there is nothing to tick and the whole
            // backup is the only thing that can be asked for.
            UIUtils.createLabel(scope, wizard.isRestore()
                ? "This backup does not report which collections it holds, so it can only be restored whole."
                : "No collections on this connection yet.");
        }

        Composite patternRow = UIUtils.createComposite(scope, 2);
        patternRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        UIUtils.createLabel(patternRow, "Also match");
        patternText = new Text(patternRow, SWT.BORDER);
        patternText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        patternText.setMessage("Company1*, Docs_?");
        patternText.setToolTipText(
            "Comma-separated names or patterns. Weaviate expands * and ? server-side, "
                + "against the collections on the server when backing up and against the "
                + "backup's own collections when restoring.");
        patternText.addModifyListener(e -> updateState());

        patternHint = UIUtils.createLabel(scope, "");
        patternHint.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        // Restore the previous choice. Exclude wins the tie because it is the one that cannot be
        // confused with the default: a non-empty include with "all" selected would be ambiguous.
        List<String> savedInclude = settings.getIncludeCollections();
        List<String> savedExclude = settings.getExcludeCollections();
        allCollections.setSelection(savedInclude.isEmpty() && savedExclude.isEmpty());
        includeCollections.setSelection(!savedInclude.isEmpty());
        excludeCollections.setSelection(!savedExclude.isEmpty());
        restoreChoice(savedExclude.isEmpty() ? savedInclude : savedExclude, known);

        Composite advanced = UIUtils.createComposite(area, 2);
        advanced.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        UIUtils.createLabel(advanced, "CPU percentage");
        cpuSpinner = new Spinner(advanced, SWT.BORDER);
        cpuSpinner.setMinimum(CPU_MIN);
        cpuSpinner.setMaximum(CPU_MAX);
        cpuSpinner.setSelection(
            settings.getCpuPercentage() == null ? CPU_DEFAULT : settings.getCpuPercentage());
        cpuSpinner.setToolTipText(
            "How much of each node's CPU the server may use. Weaviate accepts 1 to 80.");

        setControl(area);
        updateState();
    }

    /**
     * Creating offers the collections on the server; restoring offers the ones in the backup.
     * <p>
     * Restoring against the backup's list rather than the server's is not a detail: the two differ
     * exactly when a restore is worth doing, and offering a collection the backup does not contain
     * would produce "class X doesn't exist in the backup".
     */
    @NotNull
    private List<String> availableCollections() {
        if (wizard.isRestore()) {
            WeaviateBackupNode backup = wizard.getSelectedBackup();
            return backup == null ? List.of() : backup.getBackup().collections();
        }
        WeaviateDataSource dataSource = wizard.getWeaviateDataSource();
        if (dataSource == null) {
            return List.of();
        }
        try {
            List<String> names = new ArrayList<>();
            for (WeaviateCollection collection : dataSource.getCollections(new VoidProgressMonitor())) {
                names.add(collection.getName());
            }
            return names;
        } catch (Exception e) {
            setErrorMessage("Cannot read collections: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Puts a saved choice back, splitting it into ticks for names that are on the list and pattern
     * text for everything else -- which is how a pattern survives a round trip through a saved task.
     */
    private void restoreChoice(@NotNull List<String> saved, @NotNull List<String> known) {
        List<String> patterns = new ArrayList<>();
        for (String entry : saved) {
            if (!known.contains(entry)) {
                patterns.add(entry);
            }
        }
        for (TableItem item : collectionTable.getItems()) {
            item.setChecked(saved.contains(item.getText()));
        }
        patternText.setText(String.join(", ", patterns));
    }

    private boolean isAll() {
        return allCollections.getSelection();
    }

    private void updateState() {
        boolean choosing = !isAll();
        collectionTable.setEnabled(choosing);
        patternText.setEnabled(choosing);

        List<String> chosen = chosenEntries();
        patternHint.setText(choosing && !chosen.isEmpty()
            ? chosen.size() + " entr" + (chosen.size() == 1 ? "y" : "ies") + ": "
                + String.join(", ", chosen)
            : "");

        String id = idText.getText().trim();
        if (id.isEmpty()) {
            setErrorMessage("A backup ID is required");
            setPageComplete(false);
            return;
        }
        if (!WeaviateBackupSettings.VALID_ID.matcher(id).matches()) {
            setErrorMessage("A backup ID may contain only lowercase letters, digits, - and _");
            setPageComplete(false);
            return;
        }
        if (choosing && chosen.isEmpty()) {
            setErrorMessage("Tick a collection or type a pattern, or choose everything");
            setPageComplete(false);
            return;
        }
        setErrorMessage(null);
        setPageComplete(true);
    }

    /**
     * The ticked names plus anything typed, in one list. The server takes names and patterns in
     * the same field and expands the patterns itself, so there is nothing to keep apart here.
     */
    @NotNull
    private List<String> chosenEntries() {
        Set<String> entries = new LinkedHashSet<>();
        if (collectionTable != null) {
            for (TableItem item : collectionTable.getItems()) {
                if (item.getChecked()) {
                    entries.add(item.getText());
                }
            }
        }
        if (patternText != null) {
            for (String part : patternText.getText().split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    entries.add(trimmed);
                }
            }
        }
        return new ArrayList<>(entries);
    }

    void saveSettings() {
        WeaviateBackupSettings settings = wizard.getSettings();
        settings.setBackupId(idText.getText().trim());

        List<String> chosen = isAll() ? List.of() : chosenEntries();
        boolean excluding = excludeCollections.getSelection();
        settings.setIncludeCollections(excluding ? List.of() : chosen);
        settings.setExcludeCollections(excluding ? chosen : List.of());
        settings.setCpuPercentage(cpuSpinner.getSelection());
    }
}
