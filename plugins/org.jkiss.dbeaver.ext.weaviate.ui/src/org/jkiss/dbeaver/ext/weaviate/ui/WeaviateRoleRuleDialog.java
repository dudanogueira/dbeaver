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
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacAction;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacKind;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRoleRule;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One rule of a role: what it applies to, and what it allows.
 * <p>
 * A rule is several of Weaviate's permissions at once. The server stores one entry per action, so
 * read, create, update and delete on the same collection are four entries carrying four identical
 * copies of the same scope; editing them separately would mean typing that scope four times and
 * keeping the copies in step. Here the scope is written once and the actions are ticked beside it.
 */
public class WeaviateRoleRuleDialog extends BaseDialog {

    private final List<String> kinds;
    private final WeaviateRoleRule initial;
    private final boolean kindLocked;

    private Combo kindCombo;
    private Composite scopeArea;
    private Composite actionArea;
    private Label errorLabel;

    private final Map<String, Control> scopeControls = new LinkedHashMap<>();
    private final Map<String, Button> actionChecks = new LinkedHashMap<>();

    private WeaviateRoleRule result;

    /**
     * @param initial the rule to edit, or null to add one
     * @param kinds   the permission kinds this server accepts, already version-filtered
     */
    public WeaviateRoleRuleDialog(
        @NotNull Shell shell, @Nullable WeaviateRoleRule initial, @NotNull List<String> kinds
    ) {
        super(shell, initial == null ? "Add rule" : "Edit rule", null);
        this.initial = initial;
        this.kinds = kinds;
        this.kindLocked = initial != null;
    }

    @Nullable
    public WeaviateRoleRule getRule() {
        return result;
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

        Composite head = UIUtils.createComposite(group, 2);
        head.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        UIUtils.createLabel(head, "Applies to");
        kindCombo = new Combo(head, SWT.READ_ONLY | SWT.BORDER);
        kindCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        for (String kind : kinds) {
            kindCombo.add(WeaviateRbacKind.displayName(kind));
        }
        String startingKind = initial == null ? kinds.get(0) : kindFor(initial);
        kindCombo.select(Math.max(0, kinds.indexOf(startingKind)));
        // The kind decides which scope fields and which actions exist, so changing it on an
        // existing rule would mean discarding both. Add a new rule instead.
        kindCombo.setEnabled(!kindLocked);
        kindCombo.addSelectionListener(
            SelectionListener.widgetSelectedAdapter(e -> rebuild()));

        scopeArea = UIUtils.createComposite(group, 2);
        scopeArea.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        UIUtils.createLabel(group, "Allow");
        actionArea = UIUtils.createComposite(group, 2);
        actionArea.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        errorLabel = new Label(group, SWT.WRAP);
        errorLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        rebuild();
        return area;
    }

    @NotNull
    private String selectedKind() {
        int index = kindCombo.getSelectionIndex();
        return index < 0 || index >= kinds.size() ? kinds.get(0) : kinds.get(index);
    }

    @NotNull
    private static String kindFor(@NotNull WeaviateRoleRule rule) {
        return rule.kind() == null ? rule.domain() : rule.kind();
    }

    /** Lays out the scope boxes and action checkboxes for whichever kind is selected. */
    private void rebuild() {
        String kind = selectedKind();
        for (Control child : scopeArea.getChildren()) {
            child.dispose();
        }
        for (Control child : actionArea.getChildren()) {
            child.dispose();
        }
        scopeControls.clear();
        actionChecks.clear();

        Map<String, String> scope = initial != null && kindFor(initial).equals(kind)
            ? initial.scope() : WeaviateRoleRule.blank(kind).scope();

        for (String field : WeaviateRbacKind.fieldsOf(kind)) {
            UIUtils.createLabel(scopeArea, WeaviateRbacKind.displayName(field));
            String value = scope.getOrDefault(field, WeaviateRoleRule.defaultFor(field));
            List<String> choices = WeaviateRoleRule.choicesFor(field);
            if (choices.isEmpty()) {
                Text text = new Text(scopeArea, SWT.BORDER);
                text.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
                text.setText(value);
                // The server reads these as an exact name or a pattern, so this is a text box
                // rather than a picker -- a name that does not exist yet is a legitimate thing to
                // grant against.
                text.setMessage("* (everything)");
                scopeControls.put(field, text);
            } else {
                Combo combo = new Combo(scopeArea, SWT.READ_ONLY | SWT.BORDER);
                combo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
                choices.forEach(combo::add);
                combo.select(Math.max(0, choices.indexOf(value)));
                scopeControls.put(field, combo);
            }
        }
        if (WeaviateRbacKind.fieldsOf(kind).isEmpty()) {
            UIUtils.createLabel(scopeArea, "Everything");
            UIUtils.createLabel(scopeArea, "this permission takes no scope");
        }

        Set<String> ticked = initial != null && kindFor(initial).equals(kind)
            ? initial.actions() : Set.of();
        for (String action : WeaviateRbacAction.actionsOf(kind)) {
            Button check = UIUtils.createCheckbox(actionArea,
                WeaviateRbacAction.verbOf(action), action, ticked.contains(action), 1);
            check.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> clearError()));
            actionChecks.put(action, check);
            UIUtils.createLabel(actionArea, action);
        }

        scopeArea.getParent().layout(true, true);
    }

    private void clearError() {
        if (errorLabel != null && !errorLabel.isDisposed()) {
            errorLabel.setText("");
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, initial == null ? "Add" : "Apply", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed() {
        Set<String> actions = new LinkedHashSet<>();
        for (Map.Entry<String, Button> e : actionChecks.entrySet()) {
            if (e.getValue().getSelection()) {
                actions.add(e.getKey());
            }
        }
        if (actions.isEmpty()) {
            // A rule with no action is not a narrower rule, it is no rule at all -- and saving one
            // would quietly drop it.
            errorLabel.setText("Tick at least one action, or cancel to leave the rule out.");
            errorLabel.getParent().layout(true, true);
            return;
        }
        Map<String, String> scope = new LinkedHashMap<>();
        List<String> blank = new ArrayList<>();
        for (Map.Entry<String, Control> e : scopeControls.entrySet()) {
            String value = e.getValue() instanceof Text text
                ? text.getText().trim()
                : ((Combo) e.getValue()).getText();
            if (value.isEmpty()) {
                blank.add(e.getKey());
                value = WeaviateRoleRule.defaultFor(e.getKey());
            }
            scope.put(e.getKey(), value);
        }
        if (!blank.isEmpty()) {
            // Empty means "everything" to the server. Say so rather than silently granting it.
            errorLabel.setText("Left blank, " + String.join(" and ", blank)
                + " means everything. Press Add again to accept that, or type a name.");
            for (String field : blank) {
                if (scopeControls.get(field) instanceof Text text) {
                    text.setText(WeaviateRoleRule.defaultFor(field));
                }
            }
            errorLabel.getParent().layout(true, true);
            return;
        }
        String kind = selectedKind();
        result = new WeaviateRoleRule(
            WeaviateRbacKind.fieldsOf(kind).isEmpty() ? null : kind, scope, actions);
        super.okPressed();
    }
}
