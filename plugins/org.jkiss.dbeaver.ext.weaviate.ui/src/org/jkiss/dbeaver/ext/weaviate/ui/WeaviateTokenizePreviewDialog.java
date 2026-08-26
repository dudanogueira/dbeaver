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
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTokenPreview;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.Map;

/**
 * Shows how a property's analyzer splits a piece of text.
 * <p>
 * The question this answers is "why did BM25 match that?", which is usually the tokenizer doing
 * something the user did not picture -- lowercasing, dropping punctuation, or (for a FIELD
 * property) not splitting at all.
 * <p>
 * Both token lists are shown side by side. Weaviate reports what it would write to the index and
 * what the same text produces as a query, and although they have matched for every standard
 * tokenization, the server models them separately; showing one would quietly pick a side.
 */
public class WeaviateTokenizePreviewDialog extends BaseDialog {

    private static final Log log = Log.getLog(WeaviateTokenizePreviewDialog.class);

    private static final int TOKENIZE_ID = IDialogConstants.CLIENT_ID + 1;

    private final WeaviateCollection collection;
    /** Tokenizable property name -> its tokenization, in schema order. */
    private final Map<String, String> properties;
    private String propertyName;

    private Combo propertyCombo;
    private Label tokenizerLabel;
    private Text inputText;
    private List indexedList;
    private List queryList;
    private Label statusLabel;
    private Label errorLabel;

    /**
     * @param properties every tokenizable property of the collection, mapped to its tokenization,
     *                   resolved by the caller. The dialog performs no schema I/O itself -- the
     *                   same split {@code WeaviateTenantSelectDialog} uses, where the handler does
     *                   the round-trip under a progress monitor before opening anything.
     */
    public WeaviateTokenizePreviewDialog(
        @NotNull Shell shell,
        @NotNull WeaviateCollection collection,
        @NotNull String propertyName,
        @NotNull Map<String, String> properties
    ) {
        super(shell, MessageFormat.format(
            WeaviateUIMessages.tokenize_dialog_title, collection.getName(), propertyName), null);
        this.collection = collection;
        this.propertyName = propertyName;
        this.properties = properties;
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Composite createDialogArea(Composite parent) {
        Composite area = super.createDialogArea(parent);
        Composite group = UIUtils.createComposite(area, 1);
        group.setLayoutData(new GridData(GridData.FILL_BOTH));

        // Switching property without leaving the dialog is the point of the combo: comparing two
        // tokenizers on one piece of text is the common case, and doing that through the context
        // menu means retyping the text every time.
        //
        // Scoped to this collection deliberately. Offering every collection would mean picking a
        // collection in order to pick a property, which is what the navigator already does.
        Composite header = new Composite(group, SWT.NONE);
        GridLayout headerLayout = new GridLayout(3, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        header.setLayout(headerLayout);
        header.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        UIUtils.createLabel(header, WeaviateUIMessages.tokenize_dialog_property);
        propertyCombo = new Combo(header, SWT.DROP_DOWN | SWT.READ_ONLY);
        for (String name : properties.keySet()) {
            propertyCombo.add(name);
        }
        int initial = propertyCombo.indexOf(propertyName);
        propertyCombo.select(Math.max(initial, 0));
        propertyCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                propertyName = propertyCombo.getText();
                updateForSelectedProperty();
                // Re-run against the new property rather than leaving the old tokens on screen
                // under a new heading, which would read as this property's output.
                if (!inputText.getText().isEmpty()) {
                    tokenize();
                } else {
                    showTokens(null);
                }
            }
        });

        tokenizerLabel = new Label(header, SWT.NONE);
        tokenizerLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        inputText = new Text(group, SWT.BORDER | SWT.MULTI | SWT.WRAP | SWT.V_SCROLL);
        GridData inputGd = new GridData(GridData.FILL_HORIZONTAL);
        inputGd.heightHint = 70;
        inputGd.widthHint = 560;
        inputText.setLayoutData(inputGd);
        inputText.setMessage(WeaviateUIMessages.tokenize_dialog_input_hint);
        // Clear a stale complaint as soon as the user starts fixing it.
        inputText.addModifyListener(e -> setError(null));

        Composite columns = new Composite(group, SWT.NONE);
        GridLayout columnsLayout = new GridLayout(2, true);
        columnsLayout.marginWidth = 0;
        columnsLayout.marginHeight = 0;
        columns.setLayout(columnsLayout);
        columns.setLayoutData(new GridData(GridData.FILL_BOTH));

        indexedList = createTokenColumn(columns, WeaviateUIMessages.tokenize_dialog_indexed);
        queryList = createTokenColumn(columns, WeaviateUIMessages.tokenize_dialog_query);

        statusLabel = new Label(group, SWT.NONE);
        statusLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        errorLabel = new Label(group, SWT.WRAP);
        GridData errorGd = new GridData(GridData.FILL_HORIZONTAL);
        errorGd.widthHint = 560;
        errorLabel.setLayoutData(errorGd);
        errorLabel.setForeground(group.getDisplay().getSystemColor(SWT.COLOR_RED));

        updateForSelectedProperty();
        return area;
    }

    /**
     * Re-point the window title and the tokenizer caption at the selected property.
     * <p>
     * The title has to be reset explicitly on both the field and the shell: {@code BaseDialog}
     * only stores it, and the shell's text is written once when the dialog opens. Leaving it
     * alone means a dialog headed "Tokenization of X.title" showing another property's tokens,
     * which is worse than no heading at all.
     * <p>
     * The tokenizer name comes from the schema rather than the response: the per-property endpoint
     * does not echo a tokenization back, because the caller never chose one.
     */
    private void updateForSelectedProperty() {
        String heading = MessageFormat.format(
            WeaviateUIMessages.tokenize_dialog_title, collection.getName(), propertyName);
        setTitle(heading);
        Shell shell = getShell();
        if (shell != null && !shell.isDisposed()) {
            shell.setText(heading);
        }
        if (tokenizerLabel == null || tokenizerLabel.isDisposed()) {
            return;
        }
        String tokenization = properties.get(propertyName);
        tokenizerLabel.setText(MessageFormat.format(WeaviateUIMessages.tokenize_dialog_prompt,
            tokenization == null ? "default" : tokenization));
        tokenizerLabel.getParent().layout(true, true);
    }

    @NotNull
    private List createTokenColumn(@NotNull Composite parent, @NotNull String title) {
        Composite column = UIUtils.createComposite(parent, 1);
        column.setLayoutData(new GridData(GridData.FILL_BOTH));
        UIUtils.createLabel(column, title);

        List list = new List(column, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.heightHint = 220;
        list.setLayoutData(gd);
        // Tokens are machine output: whitespace and case are the whole point, so they get a font
        // that does not hide either.
        list.setFont(UIUtils.getMonospaceFont());
        return list;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, TOKENIZE_ID, WeaviateUIMessages.tokenize_dialog_run, true);
        super.createButtonsForButtonBar(parent);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == TOKENIZE_ID) {
            tokenize();
            return;
        }
        super.buttonPressed(buttonId);
    }

    private void tokenize() {
        String text = inputText.getText();
        if (text.isEmpty()) {
            setError(WeaviateUIMessages.tokenize_dialog_no_text);
            return;
        }
        setError(null);

        WeaviateTokenPreview[] holder = new WeaviateTokenPreview[1];
        try {
            // A server round-trip, so it runs with a progress dialog rather than freezing the
            // workbench on a slow or unreachable server.
            UIUtils.runInProgressService(monitor -> {
                try {
                    holder[0] = collection.previewTokenization(monitor, propertyName, text);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            // Refusals are ordinary here -- a property whose type is not tokenized, or a tokenizer
            // the server was not built with -- so they are reported inline rather than as a modal
            // failure, and the dialog stays open with the text intact.
            Throwable cause = e.getTargetException();
            log.debug("Tokenize failed for " + propertyName, cause);
            setError(cause.getMessage() == null ? cause.toString() : cause.getMessage());
            showTokens(null);
            return;
        } catch (InterruptedException e) {
            return;
        }
        showTokens(holder[0]);
    }

    private void showTokens(@Nullable WeaviateTokenPreview preview) {
        indexedList.removeAll();
        queryList.removeAll();
        if (preview == null) {
            statusLabel.setText("");
            return;
        }
        fill(indexedList, preview.getIndexed());
        fill(queryList, preview.getQuery());
        statusLabel.setText(preview.indexedDiffersFromQuery()
            ? WeaviateUIMessages.tokenize_dialog_differ
            : WeaviateUIMessages.tokenize_dialog_same);
    }

    private void fill(@NotNull List list, @NotNull java.util.List<String> tokens) {
        if (tokens.isEmpty()) {
            list.add(WeaviateUIMessages.tokenize_dialog_empty);
            return;
        }
        for (String token : tokens) {
            list.add(token);
        }
    }

    private void setError(@Nullable String message) {
        if (errorLabel == null || errorLabel.isDisposed()) {
            return;
        }
        errorLabel.setText(message == null ? "" : message);
        errorLabel.getParent().layout(true, true);
    }
}
