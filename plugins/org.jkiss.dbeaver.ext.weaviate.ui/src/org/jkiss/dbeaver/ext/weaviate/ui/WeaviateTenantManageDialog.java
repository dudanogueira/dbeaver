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
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenant;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantFilter;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantStatus;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;

import java.lang.reflect.InvocationTargetException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists a collection's tenants and activates or deactivates them, one at a time or by the
 * thousand.
 * <p>
 * Two things about scale shape this dialog. Reading is cheap -- the server hands over every
 * tenant in one response, about 190 KiB and 25 ms for 4000 -- so the whole list is held in
 * memory and filtered locally, and there is no paging to get wrong. Rendering is the expensive
 * half, so the table is {@link SWT#VIRTUAL}: it asks for the rows it is about to draw and no
 * others, which keeps opening on a 4000-tenant collection as fast as opening on a nine-tenant one.
 * <p>
 * The bulk actions deliberately name their own scope in their labels. A button reading
 * "Deactivate (750 matching)" cannot be confused with one reading "Deactivate (2 selected)",
 * where a button labelled just "Deactivate" would leave the user to work out which set was
 * about to be changed from somewhere else on screen.
 */
public class WeaviateTenantManageDialog extends BaseDialog {

    private static final Log log = Log.getLog(WeaviateTenantManageDialog.class);

    /**
     * Above this many changes the action asks first. One or two tenants is ordinary interactive
     * work; several hundred is a decision, and deactivating a tenant makes its data unreadable
     * until someone puts it back.
     */
    private static final int CONFIRM_THRESHOLD = 5;

    /** Wide enough for the longest status label; the name column takes the rest. */
    private static final int STATUS_COLUMN_WIDTH = 90;

    private final WeaviateCollection collection;

    private List<WeaviateTenant> allTenants;
    private List<WeaviateTenant> visible = new ArrayList<>();

    private Text filterText;
    private Table table;
    private Label countLabel;
    private Button activateButton;
    private Button deactivateButton;
    private Font boldFont;
    private Color inactiveColor;

    public WeaviateTenantManageDialog(
        @NotNull Shell shell,
        @NotNull WeaviateCollection collection,
        @NotNull List<WeaviateTenant> tenants
    ) {
        super(shell, MessageFormat.format(
            WeaviateUIMessages.tenant_manage_title, collection.getName()), null);
        this.collection = collection;
        this.allTenants = tenants;
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

        boldFont = UIUtils.makeBoldFont(group.getFont());
        group.addDisposeListener(e -> boldFont.dispose());
        inactiveColor = group.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);

        UIUtils.createLabel(group, WeaviateUIMessages.tenant_manage_prompt);

        filterText = new Text(group, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        filterText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        filterText.setMessage(WeaviateUIMessages.tenant_manage_filter_hint);
        filterText.addModifyListener(e -> applyFilter());

        table = new Table(group, SWT.BORDER | SWT.MULTI | SWT.FULL_SELECTION | SWT.VIRTUAL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableGd = new GridData(GridData.FILL_BOTH);
        tableGd.heightHint = 320;
        tableGd.widthHint = 460;
        table.setLayoutData(tableGd);
        TableColumn nameColumn =
            UIUtils.createTableColumn(table, SWT.LEFT, WeaviateUIMessages.tenant_manage_column_name);
        TableColumn statusColumn =
            UIUtils.createTableColumn(table, SWT.LEFT, WeaviateUIMessages.tenant_manage_column_status);
        // Widths are set rather than packed. UIUtils.packColumns calls TableColumn#pack, which
        // measures every row -- on a virtual table that means asking for all 4000 of them, which
        // is the one thing this table exists to avoid.
        statusColumn.setWidth(STATUS_COLUMN_WIDTH);
        nameColumn.setWidth(tableGd.widthHint - STATUS_COLUMN_WIDTH);
        table.addListener(SWT.Resize, e -> {
            int available = table.getClientArea().width - STATUS_COLUMN_WIDTH;
            if (available > 80) {
                nameColumn.setWidth(available);
            }
        });
        // The whole point of SWT.VIRTUAL: rows are filled in as they are about to be painted,
        // so a 4000-tenant collection costs the same to open as a 9-tenant one. The index comes
        // from the event -- Table#indexOf is a linear scan, which would make painting the table
        // quadratic in the number of tenants.
        table.addListener(SWT.SetData, event -> {
            TableItem item = (TableItem) event.item;
            if (event.index < 0 || event.index >= visible.size()) {
                return;
            }
            WeaviateTenant tenant = visible.get(event.index);
            item.setText(0, tenant.name());
            item.setText(1, tenant.status().getLabel());
            if (!tenant.isActive()) {
                item.setForeground(inactiveColor);
            }
            // Marks the tenant the Data tab is currently reading, which is the one whose
            // deactivation the user is most likely to regret.
            if (tenant.name().equals(collection.getQuerySpec().getTenant())) {
                item.setFont(boldFont);
            }
        });
        table.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> updateButtons()));

        countLabel = new Label(group, SWT.NONE);
        countLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Composite actions = UIUtils.createComposite(group, 2);
        actions.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
        ((GridLayout) actions.getLayout()).marginHeight = 0;
        activateButton = UIUtils.createDialogButton(actions, "", SelectionListener
            .widgetSelectedAdapter(e -> changeStatus(WeaviateTenantStatus.ACTIVE)));
        deactivateButton = UIUtils.createDialogButton(actions, "", SelectionListener
            .widgetSelectedAdapter(e -> changeStatus(WeaviateTenantStatus.INACTIVE)));

        applyFilter();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        // Nothing here is staged and applied on OK -- each action reaches the server as it is
        // pressed -- so an OK button would suggest a commit step that does not exist.
        createButton(parent, IDialogConstants.CLOSE_ID, IDialogConstants.CLOSE_LABEL, true);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == IDialogConstants.CLOSE_ID) {
            setReturnCode(OK);
            close();
            return;
        }
        super.buttonPressed(buttonId);
    }

    @Override
    protected Control createContents(Composite parent) {
        Control contents = super.createContents(parent);
        updateButtons();
        return contents;
    }

    private void applyFilter() {
        WeaviateTenantFilter filter = WeaviateTenantFilter.of(filterText.getText());
        visible = filter.filter(allTenants);
        // setItemCount first, then clearAll: clearing only reaches the rows that exist at the
        // time, so doing it the other way round leaves rows added by a widening filter showing
        // whatever the previous filter had put in them.
        table.setItemCount(visible.size());
        table.clearAll();
        table.setTopIndex(0);
        // A selection is a set of row indices, and the indices now mean different tenants.
        // Keeping it would let a bulk action run against rows the user never picked.
        table.deselectAll();

        int inactive = 0;
        for (WeaviateTenant tenant : visible) {
            if (!tenant.isActive()) {
                inactive++;
            }
        }
        String scope = filter.isMatchAll()
            ? WeaviateUIMessages.tenant_manage_scope_all
            : filter.isWildcard()
                ? WeaviateUIMessages.tenant_manage_scope_pattern
                : WeaviateUIMessages.tenant_manage_scope_contains;
        countLabel.setText(MessageFormat.format(WeaviateUIMessages.tenant_manage_count,
            visible.size(), allTenants.size(), visible.size() - inactive, inactive, scope));
        updateButtons();
    }

    /**
     * The set an action would change: the selected rows when there are any, otherwise everything
     * the filter matches.
     */
    @NotNull
    private List<WeaviateTenant> scope() {
        int[] indices = table.getSelectionIndices();
        if (indices.length == 0) {
            return visible;
        }
        List<WeaviateTenant> selected = new ArrayList<>(indices.length);
        for (int index : indices) {
            selected.add(visible.get(index));
        }
        return selected;
    }

    private void updateButtons() {
        if (activateButton == null || activateButton.isDisposed()) {
            return;
        }
        List<WeaviateTenant> scope = scope();
        boolean bySelection = table.getSelectionCount() > 0;
        String template = bySelection
            ? WeaviateUIMessages.tenant_manage_scope_selected_suffix
            : WeaviateUIMessages.tenant_manage_scope_matching_suffix;
        String suffix = MessageFormat.format(template, scope.size());

        int toActivate = 0;
        int toDeactivate = 0;
        for (WeaviateTenant tenant : scope) {
            if (tenant.status() == WeaviateTenantStatus.ACTIVE) {
                toDeactivate++;
            } else if (tenant.status() == WeaviateTenantStatus.INACTIVE) {
                toActivate++;
            }
        }
        activateButton.setText(WeaviateUIMessages.tenant_manage_activate + suffix);
        deactivateButton.setText(WeaviateUIMessages.tenant_manage_deactivate + suffix);
        // Disabled when there is nothing left to do, so a button that would be a no-op says so
        // instead of reporting "0 tenants changed" after a round trip.
        activateButton.setEnabled(toActivate > 0);
        deactivateButton.setEnabled(toDeactivate > 0);
        activateButton.getParent().layout();
    }

    private void changeStatus(@NotNull WeaviateTenantStatus target) {
        List<WeaviateTenant> scope = scope();
        List<WeaviateTenant> changing = new ArrayList<>();
        for (WeaviateTenant tenant : scope) {
            if (tenant.status() != target && tenant.status().isSettable()) {
                changing.add(tenant);
            }
        }
        if (changing.isEmpty()) {
            return;
        }
        int alreadyThere = scope.size() - changing.size();
        if (changing.size() > CONFIRM_THRESHOLD) {
            String question = MessageFormat.format(
                target == WeaviateTenantStatus.ACTIVE
                    ? WeaviateUIMessages.tenant_manage_confirm_activate
                    : WeaviateUIMessages.tenant_manage_confirm_deactivate,
                changing.size(), collection.getName())
                + (alreadyThere == 0 ? "" : "\n\n" + MessageFormat.format(
                    WeaviateUIMessages.tenant_manage_confirm_unchanged, alreadyThere));
            if (!UIUtils.confirmAction(getShell(), getShell().getText(), question)) {
                return;
            }
        }

        int[] changed = new int[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    changed[0] = collection.setTenantStatus(monitor, changing, target);
                    // Cancelling stops at a chunk boundary, so what the server holds now is not
                    // necessarily what was asked for. Re-read rather than patch the local list.
                    allTenants = collection.listTenants(monitor);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot update tenants", e.getTargetException());
            DBWorkbench.getPlatformUI().showError(
                getShell().getText(), WeaviateUIMessages.tenant_manage_failed, e.getTargetException());
            reloadQuietly();
            return;
        } catch (InterruptedException e) {
            reloadQuietly();
            return;
        }

        applyFilter();
        countLabel.setText(MessageFormat.format(
            target == WeaviateTenantStatus.ACTIVE
                ? WeaviateUIMessages.tenant_manage_activated
                : WeaviateUIMessages.tenant_manage_deactivated,
            changed[0]) + "  " + countLabel.getText());
    }

    /**
     * Re-reads the list after a failed or cancelled change, so the table cannot keep showing
     * states the server no longer agrees with.
     */
    private void reloadQuietly() {
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    allTenants = collection.listTenants(monitor);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
            applyFilter();
        } catch (InvocationTargetException | InterruptedException e) {
            log.debug("Cannot re-read tenants after a failed update", e);
        }
    }
}
