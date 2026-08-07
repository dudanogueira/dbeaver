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

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Link;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateSchemaJson;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.ShellUtils;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;
import org.jkiss.dbeaver.ui.dialogs.DialogUtils;
import org.jkiss.utils.CommonUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Editor for a Weaviate collection definition, expressed as REST schema JSON.
 * <p>
 * Weaviate collections have a large and fast-moving configuration surface (vectorizers,
 * quantization, reranker and generative modules, multi-tenancy, replication). Rather than
 * mirroring all of it in SWT - which would go stale with every server release - this dialog
 * edits the schema document directly and points at the
 * <a href="https://weaviate.github.io/weaviate-add-collection/">weaviate-add-collection</a>
 * builder for composing one interactively. That tool emits exactly the format accepted here.
 */
public class WeaviateCollectionEditDialog extends BaseDialog {

    private static final Log log = Log.getLog(WeaviateCollectionEditDialog.class);

    public static final String SCHEMA_BUILDER_URL = "https://weaviate.github.io/weaviate-add-collection/";

    private static final int LOAD_FILE_ID = IDialogConstants.CLIENT_ID + 1;

    private final String initialJson;
    private String json;
    private Text jsonText;
    private Label errorLabel;

    public WeaviateCollectionEditDialog(@NotNull Shell shell, @Nullable String initialJson) {
        super(shell, WeaviateUIMessages.collection_dialog_title, null);
        this.initialJson = CommonUtils.notEmpty(initialJson);
        this.json = this.initialJson;
    }

    /** The edited definition, valid only after the dialog is closed with OK. */
    @NotNull
    public String getJson() {
        return json;
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Composite createDialogArea(Composite parent) {
        Composite composite = super.createDialogArea(parent);

        Link link = new Link(composite, SWT.NONE);
        link.setText(WeaviateUIMessages.collection_dialog_builder_link);
        link.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        link.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                ShellUtils.launchProgram(SCHEMA_BUILDER_URL);
            }
        });

        Composite editorArea = UIUtils.createComposite(composite, 1);
        editorArea.setLayoutData(new GridData(GridData.FILL_BOTH));

        Label label = new Label(editorArea, SWT.NONE);
        label.setText(WeaviateUIMessages.collection_dialog_json_label);

        jsonText = new Text(editorArea, SWT.BORDER | SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.widthHint = 600;
        gd.heightHint = 400;
        jsonText.setLayoutData(gd);
        jsonText.setFont(UIUtils.getMonospaceFont());
        jsonText.setText(initialJson);
        jsonText.addModifyListener(e -> {
            // Clear a stale complaint as soon as the user starts fixing it.
            setError(null);
            updateButtons();
        });

        errorLabel = new Label(editorArea, SWT.WRAP);
        GridData errorGd = new GridData(GridData.FILL_HORIZONTAL);
        errorGd.widthHint = 600;
        errorLabel.setLayoutData(errorGd);
        errorLabel.setForeground(errorLabel.getDisplay().getSystemColor(SWT.COLOR_RED));

        return composite;
    }

    private void setError(@Nullable String message) {
        if (errorLabel != null && !errorLabel.isDisposed()) {
            errorLabel.setText(message == null ? "" : message);
            errorLabel.getParent().layout(true, true);
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, LOAD_FILE_ID, WeaviateUIMessages.collection_dialog_load_file, false);
        super.createButtonsForButtonBar(parent);
        updateButtons();
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == LOAD_FILE_ID) {
            loadFromFile();
            return;
        }
        if (buttonId == IDialogConstants.OK_ID) {
            String candidate = jsonText.getText();
            try {
                // Validate before closing. Deferring this to save time would pop an error after
                // the dialog is gone, losing whatever the user had typed.
                WeaviateSchemaJson.validate(candidate);
            } catch (DBException e) {
                setError(e.getMessage());
                return;
            }
            json = candidate;
        }
        super.buttonPressed(buttonId);
    }

    private void loadFromFile() {
        File file = DialogUtils.openFile(getShell(), new String[]{"*.json", "*", "*.*"});
        if (file == null) {
            return;
        }
        try {
            jsonText.setText(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("Cannot read collection definition from " + file, e);
            DBWorkbench.getPlatformUI().showError(
                WeaviateUIMessages.collection_dialog_title,
                NLS.bind(WeaviateUIMessages.collection_dialog_read_error, file.getName(), e.getMessage()),
                e);
        }
    }

    /**
     * Only guards against an empty document. Structural validation happens on save, where the
     * model layer can report precisely what is wrong; duplicating it here would mean keeping two
     * validators in step.
     */
    private void updateButtons() {
        Button okButton = getButton(IDialogConstants.OK_ID);
        if (okButton != null) {
            okButton.setEnabled(!CommonUtils.isEmptyTrimmed(jsonText.getText()));
        }
    }

    @Override
    protected Control createContents(Composite parent) {
        Control contents = super.createContents(parent);
        updateButtons();
        return contents;
    }
}
