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
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;

import java.text.MessageFormat;

/**
 * Shows an API key once, because once is all the server will show it.
 * <p>
 * Weaviate returns the key from {@code POST /users/db/{id}} and from {@code rotate-key} and then
 * never again -- it stores a hash. A dialog someone can dismiss without noticing is therefore a
 * dialog that costs them the key, so this one says so plainly and puts a copy button next to it.
 * <p>
 * The key is deliberately not logged, not put in a tooltip, and not written to the tree. The only
 * thing that outlives this dialog is whatever the user copies.
 */
public class WeaviateApiKeyDialog extends BaseDialog {

    private final String userId;
    private final String apiKey;
    private final boolean rotated;

    public WeaviateApiKeyDialog(
        @NotNull Shell shell, @NotNull String userId, @NotNull String apiKey, boolean rotated
    ) {
        super(shell, rotated ? "New API key" : "User created", DBIcon.STATUS_WARNING);
        this.userId = userId;
        this.apiKey = apiKey;
        this.rotated = rotated;
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

        Label headline = UIUtils.createLabel(group, MessageFormat.format(
            rotated
                ? "{0} has a new API key. The previous one no longer works."
                : "The user {0} has been created.",
            userId));
        headline.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label warning = UIUtils.createLabel(group,
            "This is the only time Weaviate will show this key. Copy it now; the server keeps "
                + "only a hash and cannot show it again.");
        warning.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Text keyText = new Text(group, SWT.BORDER | SWT.READ_ONLY | SWT.WRAP);
        GridData keyGd = new GridData(GridData.FILL_HORIZONTAL);
        keyGd.widthHint = UIUtils.getFontHeight(group) * 40;
        keyText.setLayoutData(keyGd);
        keyText.setText(apiKey);
        keyText.selectAll();

        UIUtils.createDialogButton(group, "Copy to clipboard",
            SelectionListener.widgetSelectedAdapter(e -> {
                Clipboard clipboard = new Clipboard(getShell().getDisplay());
                try {
                    clipboard.setContents(
                        new Object[]{apiKey}, new Transfer[]{TextTransfer.getInstance()});
                } finally {
                    clipboard.dispose();
                }
            }));
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        // No Cancel: the key already exists on the server, so there is nothing to call off.
        createButton(parent, IDialogConstants.OK_ID, "I have copied it", true);
    }
}
