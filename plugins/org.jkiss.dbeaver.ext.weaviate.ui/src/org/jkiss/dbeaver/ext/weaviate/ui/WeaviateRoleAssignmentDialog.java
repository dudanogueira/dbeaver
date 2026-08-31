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
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantFilter;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Picks the roles a user or a group should hold.
 * <p>
 * Reports the change as two lists rather than one set, because that is what the server takes:
 * {@code assign} and {@code revoke} are separate calls, and sending the whole set as an assign
 * would leave anything the user unticked still in place.
 * <p>
 * The filter is the tenant one. It handles plain substrings and {@code *}/{@code ?} globs, it is
 * already unit-tested, and it takes a plain string -- so it filters role names unchanged.
 */
public class WeaviateRoleAssignmentDialog extends BaseDialog {

    private final String subject;
    private final String subjectKind;
    private final List<String> available;
    private final Set<String> initiallyHeld;
    private final String banner;

    private Text filterText;
    private Table table;
    private Label countLabel;
    private List<String> visible = new ArrayList<>();
    private final Set<String> held = new LinkedHashSet<>();

    /**
     * @param available the roles that may be assigned, already filtered of the ones the server
     *                  will not accept
     * @param banner    an explanatory line, or null
     */
    public WeaviateRoleAssignmentDialog(
        @NotNull Shell shell,
        @NotNull String subject,
        @NotNull String subjectKind,
        @NotNull List<String> available,
        @NotNull Set<String> currentlyHeld,
        @Nullable String banner
    ) {
        super(shell, subjectKind + " " + subject, null);
        this.subject = subject;
        this.subjectKind = subjectKind;
        this.available = available;
        this.initiallyHeld = Set.copyOf(currentlyHeld);
        this.banner = banner;
        this.held.addAll(currentlyHeld);
    }

    /** Roles ticked that were not held before. */
    @NotNull
    public List<String> getToAssign() {
        List<String> result = new ArrayList<>();
        for (String role : held) {
            if (!initiallyHeld.contains(role)) {
                result.add(role);
            }
        }
        return result;
    }

    /** Roles held before that are no longer ticked. */
    @NotNull
    public List<String> getToRevoke() {
        List<String> result = new ArrayList<>();
        for (String role : initiallyHeld) {
            if (!held.contains(role)) {
                result.add(role);
            }
        }
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

        UIUtils.createLabel(group, MessageFormat.format(
            "Roles held by the {0} {1}.", subjectKind.toLowerCase(java.util.Locale.ROOT), subject));
        if (banner != null) {
            Label bannerLabel = UIUtils.createLabel(group, banner);
            bannerLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        }

        filterText = new Text(group, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        filterText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        filterText.setMessage("Filter by name, or a pattern such as data-*");
        filterText.addModifyListener(e -> applyFilter());

        table = new Table(group, SWT.CHECK | SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setLinesVisible(true);
        GridData tableGd = new GridData(GridData.FILL_BOTH);
        tableGd.heightHint = 240;
        tableGd.widthHint = 380;
        table.setLayoutData(tableGd);
        table.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            syncFromTable();
            updateCount();
        }));

        countLabel = new Label(group, SWT.NONE);
        countLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        applyFilter();
        return area;
    }

    /** Ticks survive filtering: narrowing the list to find one more must not drop the others. */
    private void applyFilter() {
        syncFromTable();
        WeaviateTenantFilter filter = WeaviateTenantFilter.of(filterText.getText());
        visible = new ArrayList<>();
        for (String role : available) {
            if (filter.matches(role)) {
                visible.add(role);
            }
        }
        table.removeAll();
        for (String role : visible) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(role);
            item.setChecked(held.contains(role));
        }
        updateCount();
    }

    private void syncFromTable() {
        if (table == null || table.isDisposed()) {
            return;
        }
        TableItem[] items = table.getItems();
        for (int i = 0; i < items.length && i < visible.size(); i++) {
            if (items[i].getChecked()) {
                held.add(visible.get(i));
            } else {
                held.remove(visible.get(i));
            }
        }
    }

    private void updateCount() {
        if (countLabel != null && !countLabel.isDisposed()) {
            countLabel.setText(MessageFormat.format("{0} of {1} selected, showing {2}",
                held.size(), available.size(), visible.size()));
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Apply", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed() {
        syncFromTable();
        super.okPressed();
    }
}
