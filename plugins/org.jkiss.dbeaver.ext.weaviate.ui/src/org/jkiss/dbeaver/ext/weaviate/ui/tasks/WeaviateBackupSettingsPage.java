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
import java.util.List;

/**
 * What to back up, or what to bring back.
 * <p>
 * The two directions share this page because they take the same decisions. Only the source of the
 * collection list differs: creating offers what the server currently holds, restoring offers what
 * the chosen backup holds -- which is why a backup reached through the list endpoint is the only
 * one that can be restored selectively.
 */
public class WeaviateBackupSettingsPage extends WizardPage {

    /** The server clamps this to 1-80 and defaults to 50. */
    private static final int CPU_MIN = 1;
    private static final int CPU_MAX = 80;
    private static final int CPU_DEFAULT = 50;

    private final WeaviateBackupTaskWizard wizard;

    private Text idText;
    private Button allCollections;
    private Button someCollections;
    private Table collectionTable;
    private Spinner cpuSpinner;

    protected WeaviateBackupSettingsPage(@NotNull WeaviateBackupTaskWizard wizard) {
        super("weaviate.backup.settings");
        this.wizard = wizard;
        setTitle(wizard.isRestore() ? "Restore backup" : "Backup collections");
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
        allCollections = UIUtils.createRadioButton(scope, "All collections", Boolean.TRUE, onScope);
        someCollections = UIUtils.createRadioButton(scope, "Only these", Boolean.FALSE, onScope);

        collectionTable = new Table(scope, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL);
        GridData tableGd = new GridData(GridData.FILL_BOTH);
        tableGd.heightHint = 180;
        collectionTable.setLayoutData(tableGd);
        for (String name : availableCollections()) {
            new TableItem(collectionTable, SWT.NONE).setText(name);
        }
        restoreChecked(settings.getIncludeCollections());
        allCollections.setSelection(settings.getIncludeCollections().isEmpty());
        someCollections.setSelection(!settings.getIncludeCollections().isEmpty());

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
     * A backup reached by polling reports no collections at all -- the status endpoint omits them
     * -- in which case the choice collapses to all-or-nothing, which is what the server would
     * enforce anyway.
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

    private void restoreChecked(@NotNull List<String> selected) {
        for (TableItem item : collectionTable.getItems()) {
            item.setChecked(selected.contains(item.getText()));
        }
    }

    private void updateState() {
        boolean some = someCollections.getSelection();
        collectionTable.setEnabled(some);

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
        if (some && checkedCollections().isEmpty()) {
            setErrorMessage("Choose at least one collection, or back up all of them");
            setPageComplete(false);
            return;
        }
        setErrorMessage(null);
        setPageComplete(true);
    }

    @NotNull
    private List<String> checkedCollections() {
        List<String> checked = new ArrayList<>();
        for (TableItem item : collectionTable.getItems()) {
            if (item.getChecked()) {
                checked.add(item.getText());
            }
        }
        return checked;
    }

    void saveSettings() {
        WeaviateBackupSettings settings = wizard.getSettings();
        settings.setBackupId(idText.getText().trim());
        // Include only. The server refuses a request carrying both include and exclude, and one
        // checklist cannot express both, so exclude stays empty rather than being half-supported.
        settings.setIncludeCollections(
            someCollections.getSelection() ? checkedCollections() : List.of());
        settings.setExcludeCollections(List.of());
        settings.setCpuPercentage(cpuSpinner.getSelection());
    }
}
