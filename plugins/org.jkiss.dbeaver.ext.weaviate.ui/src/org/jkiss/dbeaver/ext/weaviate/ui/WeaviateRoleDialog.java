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
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacKind;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRoleRule;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Creates a role, or edits the permissions of one.
 * <p>
 * Permissions are shown as <em>rules</em> -- a scope written once with its actions ticked beside
 * it -- rather than as the server's own one-entry-per-action form. See {@link WeaviateRoleRule}.
 * <p>
 * A built-in role opens read-only. The server refuses create, update and delete on admin, root,
 * viewer and read-only with a 400, so letting someone compose an edit here would only be arranging
 * for it to be thrown away on save.
 * <p>
 * Rules this build cannot model are listed and cannot be edited: a permission from a newer server,
 * or one written by a Weaviate old enough to have used {@code manage_data}. They are still carried
 * back out unchanged, so saving a role does not quietly strip what the editor could not draw.
 */
public class WeaviateRoleDialog extends BaseDialog {

    private final String existingName;
    private final boolean builtIn;
    private final List<WeaviateRoleRule> rules = new ArrayList<>();
    private final List<String> domains;

    private Text nameText;
    private Table table;
    private Button editButton;
    private Button removeButton;
    private Label errorLabel;

    private String resultName;

    /**
     * @param existingName the role being edited, or null to create one
     * @param initialRules its current rules; empty when creating
     * @param builtIn      whether the server will refuse any change to it
     * @param domains      the permission domains this server accepts
     */
    public WeaviateRoleDialog(
        @NotNull Shell shell,
        @Nullable String existingName,
        @NotNull List<WeaviateRoleRule> initialRules,
        boolean builtIn,
        @NotNull List<String> domains
    ) {
        super(shell, existingName == null ? "Create role" : "Role " + existingName, null);
        this.existingName = existingName;
        this.builtIn = builtIn;
        this.domains = domains;
        this.rules.addAll(initialRules);
    }

    /** The role name; for an edit this is unchanged, since a role cannot be renamed. */
    @NotNull
    public String getRoleName() {
        return resultName == null ? "" : resultName;
    }

    @NotNull
    public List<WeaviateRoleRule> getRules() {
        return rules;
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

        if (builtIn) {
            Label banner = UIUtils.createLabel(group,
                "This is a built-in role. Weaviate refuses every change to it, so it is shown "
                    + "here read-only.");
            banner.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        }

        Composite head = UIUtils.createComposite(group, 2);
        head.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        UIUtils.createLabel(head, "Name");
        nameText = new Text(head, SWT.BORDER);
        nameText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        nameText.setText(existingName == null ? "" : existingName);
        // A role cannot be renamed: the name is its identity everywhere it is assigned.
        nameText.setEditable(existingName == null && !builtIn);
        if (existingName == null) {
            nameText.setMessage("e.g. data-reader");
        }
        nameText.addModifyListener(e -> clearError());

        UIUtils.createLabel(group, "Permissions");
        table = new Table(group, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        UIUtils.createTableColumn(table, SWT.LEFT, "Applies to");
        UIUtils.createTableColumn(table, SWT.LEFT, "Scope");
        UIUtils.createTableColumn(table, SWT.LEFT, "Allows");
        int em = UIUtils.getFontHeight(table);
        table.getColumn(0).setWidth(em * 12);
        table.getColumn(1).setWidth(em * 26);
        table.getColumn(2).setWidth(em * 22);
        GridData tableGd = new GridData(GridData.FILL_BOTH);
        tableGd.heightHint = table.getHeaderHeight() + table.getItemHeight() * 10;
        tableGd.widthHint = em * 62;
        table.setLayoutData(tableGd);
        table.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> updateButtons()));

        Composite buttons = UIUtils.createComposite(group, 3);
        Button addButton = UIUtils.createDialogButton(buttons, "Add rule",
            SelectionListener.widgetSelectedAdapter(e -> addRule()));
        editButton = UIUtils.createDialogButton(buttons, "Edit rule",
            SelectionListener.widgetSelectedAdapter(e -> editRule()));
        removeButton = UIUtils.createDialogButton(buttons, "Remove rule",
            SelectionListener.widgetSelectedAdapter(e -> removeRule()));
        addButton.setEnabled(!builtIn);

        errorLabel = new Label(group, SWT.WRAP);
        errorLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        refreshTable();
        return area;
    }

    private void refreshTable() {
        table.removeAll();
        for (WeaviateRoleRule rule : rules) {
            TableItem item = new TableItem(table, SWT.NONE);
            String domain = rule.domain();
            item.setText(0, WeaviateRbacKind.displayName(domain));
            String scope = rule.describeScope();
            item.setText(1, scope.isEmpty() ? "everything" : scope);
            item.setText(2, rule.isEditable()
                ? rule.describeActions()
                : rule.describeActions() + "  (not editable here)");
        }
        updateButtons();
    }

    private void updateButtons() {
        int index = table.getSelectionIndex();
        boolean editable = index >= 0 && index < rules.size()
            && rules.get(index).isEditable() && !builtIn;
        if (editButton != null && !editButton.isDisposed()) {
            editButton.setEnabled(editable);
            removeButton.setEnabled(index >= 0 && !builtIn);
        }
    }

    private void addRule() {
        WeaviateRoleRuleDialog dialog =
            new WeaviateRoleRuleDialog(getShell(), null, domains);
        if (dialog.open() == IDialogConstants.OK_ID && dialog.getRule() != null) {
            rules.add(dialog.getRule());
            refreshTable();
            table.select(rules.size() - 1);
            updateButtons();
            clearError();
        }
    }

    private void editRule() {
        int index = table.getSelectionIndex();
        if (index < 0 || index >= rules.size()) {
            return;
        }
        WeaviateRoleRuleDialog dialog =
            new WeaviateRoleRuleDialog(getShell(), rules.get(index), domains);
        if (dialog.open() == IDialogConstants.OK_ID && dialog.getRule() != null) {
            rules.set(index, dialog.getRule());
            refreshTable();
            table.select(index);
            updateButtons();
        }
    }

    private void removeRule() {
        int index = table.getSelectionIndex();
        if (index >= 0 && index < rules.size()) {
            rules.remove(index);
            refreshTable();
        }
    }

    private void clearError() {
        if (errorLabel != null && !errorLabel.isDisposed()) {
            errorLabel.setText("");
        }
    }

    private void showError(@NotNull String message) {
        errorLabel.setText(message);
        errorLabel.getParent().layout(true, true);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        if (builtIn) {
            // Nothing to save, so no button that suggests otherwise.
            createButton(parent, IDialogConstants.CANCEL_ID, "Close", true);
            return;
        }
        createButton(parent, IDialogConstants.OK_ID,
            existingName == null ? "Create" : "Save", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed() {
        String name = nameText.getText().trim();
        if (name.isEmpty()) {
            showError("A role needs a name.");
            return;
        }
        if (existingName == null && !name.matches("[A-Za-z0-9_.@-]+")) {
            // Caught here rather than by the server so the message names the rule, not a 422.
            showError("Use letters, digits, and - _ . @ only.");
            return;
        }
        if (rules.isEmpty()) {
            // Saving an empty role would strip every permission it has, which is a delete wearing
            // the word Save. Make the destructive thing be the destructive command.
            showError(existingName == null
                ? "Add at least one rule."
                : "A role with no rules grants nothing. Add a rule, or use Delete Role.");
            return;
        }
        resultName = name;
        super.okPressed();
    }
}
