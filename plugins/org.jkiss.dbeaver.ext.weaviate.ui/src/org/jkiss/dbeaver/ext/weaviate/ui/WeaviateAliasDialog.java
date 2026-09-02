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
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;

import java.util.List;

/**
 * Names an alias and picks the collection it resolves to.
 * <p>
 * One dialog in three shapes, because they are one question asked from three places:
 * <ul>
 * <li>from the connection's Aliases folder, both halves are open;</li>
 * <li>from a collection's own Aliases folder, the target is already answered and is shown rather
 *     than asked;</li>
 * <li>repointing an existing alias, the name is fixed and the target is the only thing to change.
 *     The current target is excluded from the list, since choosing it would send a request that
 *     changes nothing.</li>
 * </ul>
 * Validation stops at what can be checked here: a name, and a name that is not already a
 * collection. Everything else is the server's judgement, and its refusal is more useful than a
 * guess at its rules.
 */
public class WeaviateAliasDialog extends BaseDialog {

    private final boolean retarget;
    private final String fixedName;
    private final String fixedTarget;
    private final String currentTarget;
    private final List<String> collections;

    private Text nameText;
    private Combo targetCombo;
    private Label problem;

    private String name;
    private String target;

    private WeaviateAliasDialog(
        @NotNull Shell shell,
        @NotNull String title,
        boolean retarget,
        @Nullable String fixedName,
        @Nullable String fixedTarget,
        @Nullable String currentTarget,
        @NotNull List<String> collections
    ) {
        super(shell, title, DBIcon.TREE_SYNONYM);
        this.retarget = retarget;
        this.fixedName = fixedName;
        this.fixedTarget = fixedTarget;
        this.currentTarget = currentTarget;
        this.collections = collections;
        this.name = fixedName == null ? "" : fixedName;
        this.target = fixedTarget;
    }

    /**
     * @param presetTarget the collection whose folder this was invoked from, or null when it was
     *                     the connection-wide folder
     */
    @NotNull
    public static WeaviateAliasDialog forCreate(
        @NotNull Shell shell,
        @NotNull List<String> collections,
        @Nullable String presetTarget
    ) {
        return new WeaviateAliasDialog(shell, WeaviateUIMessages.alias_create_title,
            false, null, presetTarget, null, collections);
    }

    @NotNull
    public static WeaviateAliasDialog forRetarget(
        @NotNull Shell shell,
        @NotNull String alias,
        @NotNull String currentTarget,
        @NotNull List<String> collections
    ) {
        return new WeaviateAliasDialog(shell,
            NLS.bind(WeaviateUIMessages.alias_retarget_title, alias),
            true, alias, null, currentTarget, collections);
    }

    @NotNull
    public String getAliasName() {
        return name;
    }

    @Nullable
    public String getTargetCollection() {
        return target;
    }

    @Override
    protected Composite createDialogArea(Composite parent) {
        Composite area = super.createDialogArea(parent);
        Composite group = UIUtils.createComposite(area, 2);
        group.setLayoutData(new GridData(GridData.FILL_BOTH));

        UIUtils.createLabel(group, WeaviateUIMessages.alias_name);
        if (fixedName != null) {
            // An alias cannot be renamed: the server offers no endpoint for it, only a repoint.
            UIUtils.createLabel(group, fixedName);
        } else {
            nameText = new Text(group, SWT.BORDER);
            nameText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            nameText.setMessage(WeaviateUIMessages.alias_name_hint);
            nameText.addModifyListener(e -> {
                name = nameText.getText().strip();
                revalidate();
            });
        }

        UIUtils.createLabel(group, WeaviateUIMessages.alias_target);
        List<String> choices = targetChoices();
        if (fixedTarget != null) {
            UIUtils.createLabel(group, fixedTarget);
        } else {
            targetCombo = new Combo(group, SWT.READ_ONLY | SWT.BORDER);
            targetCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            choices.forEach(targetCombo::add);
            if (!choices.isEmpty()) {
                targetCombo.select(0);
                target = choices.get(0);
            }
            targetCombo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
                int index = targetCombo.getSelectionIndex();
                target = index < 0 ? null : choices.get(index);
                revalidate();
            }));
        }

        Label explanation = new Label(group, SWT.WRAP);
        GridData explanationGd = new GridData(GridData.FILL_HORIZONTAL);
        explanationGd.horizontalSpan = 2;
        explanationGd.widthHint = UIUtils.getFontHeight(group) * 46;
        explanation.setLayoutData(explanationGd);
        explanation.setText(retarget
            ? WeaviateUIMessages.alias_retarget_explanation
            : WeaviateUIMessages.alias_create_explanation);

        problem = new Label(group, SWT.WRAP);
        GridData problemGd = new GridData(GridData.FILL_HORIZONTAL);
        problemGd.horizontalSpan = 2;
        problemGd.widthHint = UIUtils.getFontHeight(group) * 46;
        problem.setLayoutData(problemGd);
        return area;
    }

    /**
     * The collections offered as a target.
     * <p>
     * On a repoint the current one is left out: sending it would be a request that changes
     * nothing, and an unchanged entry sitting selected in the list invites exactly that.
     */
    @NotNull
    private List<String> targetChoices() {
        if (!retarget || currentTarget == null) {
            return collections;
        }
        return collections.stream().filter(c -> !c.equalsIgnoreCase(currentTarget)).toList();
    }

    private void revalidate() {
        String message = validate();
        if (problem != null && !problem.isDisposed()) {
            problem.setText(message == null ? "" : message);
            problem.getParent().layout(true, true);
        }
        if (getButton(IDialogConstants.OK_ID) != null) {
            // A blank name is incomplete rather than wrong, so it disables the button without
            // putting an accusation on screen before anything has been typed.
            getButton(IDialogConstants.OK_ID).setEnabled(message == null && !name.isBlank());
        }
    }

    /** @return what is wrong, or null when nothing is */
    @Nullable
    private String validate() {
        if (target == null || target.isBlank()) {
            return WeaviateUIMessages.alias_no_collections;
        }
        if (name.isBlank()) {
            return null;
        }
        if (name.chars().anyMatch(Character::isWhitespace)) {
            return WeaviateUIMessages.alias_name_spaces;
        }
        for (String collection : collections) {
            if (collection.equalsIgnoreCase(name)) {
                // The server refuses this, and its reason is worth pre-empting: an alias sharing a
                // collection's name would make every query by that name ambiguous.
                return NLS.bind(WeaviateUIMessages.alias_name_taken, collection);
            }
        }
        return null;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID,
            retarget ? WeaviateUIMessages.alias_button_repoint : WeaviateUIMessages.alias_button_create,
            true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        revalidate();
    }

    @Override
    protected void okPressed() {
        if (nameText != null && !nameText.isDisposed()) {
            name = nameText.getText().strip();
        }
        if (name.isBlank() || target == null || validate() != null) {
            return;
        }
        super.okPressed();
    }
}
