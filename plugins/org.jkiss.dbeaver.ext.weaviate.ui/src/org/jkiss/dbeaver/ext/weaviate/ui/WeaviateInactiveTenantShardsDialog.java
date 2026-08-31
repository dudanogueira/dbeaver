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
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantFilter;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Offers the shards that the Shards tree cannot show.
 * <p>
 * A tenant's shard only appears in the cluster API's node listing while that tenant is active, so
 * a multi-tenant collection with inactive tenants presents fewer shards than it has. Acting on
 * "every shard of this collection" therefore quietly misses some, which is the kind of omission
 * nobody notices until the one shard they cared about was in it.
 * <p>
 * The shards are real and writable: setting a status on an inactive tenant's shard is accepted and
 * persists, verified by setting one READONLY, activating the tenant and reading it back. So this
 * dialog offers to include them rather than to activate anything -- activation loads a tenant's
 * data into memory, and doing that as a side effect of a status change would be a much larger act
 * than the one asked for.
 * <p>
 * Nothing is ticked to begin with. These shards are invisible in the tree, so including them is an
 * addition to what was selected, and additions should be chosen rather than assumed.
 */
public class WeaviateInactiveTenantShardsDialog extends BaseDialog {

    /** One inactive tenant, which on a multi-tenant collection is also one shard name. */
    public record Entry(@NotNull String collection, @NotNull String tenant) {
    }

    private final List<Entry> allEntries;
    private final String targetStatus;

    private Text filterText;
    private Table table;
    private Label countLabel;
    private List<Entry> visible = new ArrayList<>();
    private final List<Entry> chosen = new ArrayList<>();

    public WeaviateInactiveTenantShardsDialog(
        @NotNull Shell shell,
        @NotNull List<Entry> entries,
        @NotNull String targetStatus
    ) {
        super(shell, "Inactive tenants", null);
        this.allEntries = entries;
        this.targetStatus = targetStatus;
    }

    /** The entries the user ticked. Empty means "leave them alone", which is the default. */
    @NotNull
    public List<Entry> getChosen() {
        return chosen;
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
            "{0} tenant(s) in this selection are inactive, so their shards are not listed in the "
                + "tree and will not be set to {1}.\n\nTick any you want included. Their shards can "
                + "be changed without activating them.",
            allEntries.size(), targetStatus));

        filterText = new Text(group, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        filterText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        filterText.setMessage("Filter by name, or a pattern such as acme-*");
        filterText.setToolTipText(
            "Plain text matches anywhere in the name; * and ? match as a pattern against the whole "
                + "name. The same rule the tenant dialogs use.");
        filterText.addModifyListener(e -> applyFilter());

        table = new Table(group, SWT.CHECK | SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableGd = new GridData(GridData.FILL_BOTH);
        tableGd.heightHint = 260;
        tableGd.widthHint = 460;
        table.setLayoutData(tableGd);
        TableColumn tenantColumn = UIUtils.createTableColumn(table, SWT.LEFT, "Tenant");
        TableColumn collectionColumn = UIUtils.createTableColumn(table, SWT.LEFT, "Collection");
        tenantColumn.setWidth(240);
        collectionColumn.setWidth(200);
        table.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> updateCount()));

        Composite buttons = UIUtils.createComposite(group, 2);
        UIUtils.createDialogButton(buttons, "Select all",
            SelectionListener.widgetSelectedAdapter(e -> setAllChecked(true)));
        UIUtils.createDialogButton(buttons, "Select none",
            SelectionListener.widgetSelectedAdapter(e -> setAllChecked(false)));

        countLabel = new Label(group, SWT.NONE);
        countLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        applyFilter();
        return area;
    }

    /**
     * Filtering rebuilds the rows, so what is ticked has to survive it -- someone narrowing the
     * filter to find one more tenant must not lose the ones they already picked.
     */
    private void applyFilter() {
        List<Entry> checkedBefore = checkedEntries();
        WeaviateTenantFilter filter = WeaviateTenantFilter.of(filterText.getText());

        visible = new ArrayList<>();
        for (Entry entry : allEntries) {
            if (filter.matches(entry.tenant())) {
                visible.add(entry);
            }
        }
        table.removeAll();
        for (Entry entry : visible) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(0, entry.tenant());
            item.setText(1, entry.collection());
            item.setChecked(checkedBefore.contains(entry));
        }
        updateCount();
    }

    private void setAllChecked(boolean checked) {
        for (TableItem item : table.getItems()) {
            item.setChecked(checked);
        }
        updateCount();
    }

    @NotNull
    private List<Entry> checkedEntries() {
        List<Entry> checked = new ArrayList<>();
        if (table == null || table.isDisposed()) {
            return checked;
        }
        TableItem[] items = table.getItems();
        for (int i = 0; i < items.length && i < visible.size(); i++) {
            if (items[i].getChecked()) {
                checked.add(visible.get(i));
            }
        }
        return checked;
    }

    private void updateCount() {
        if (countLabel != null && !countLabel.isDisposed()) {
            countLabel.setText(MessageFormat.format("{0} of {1} selected, showing {2}",
                checkedEntries().size(), allEntries.size(), visible.size()));
        }
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        // "Continue" rather than OK: the dialog is a step inside a larger action, and ticking
        // nothing is a legitimate answer that carries on without these shards.
        createButton(parent, IDialogConstants.OK_ID, "Continue", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void okPressed() {
        chosen.clear();
        chosen.addAll(checkedEntries());
        super.okPressed();
    }
}
