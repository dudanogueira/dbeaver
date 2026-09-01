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
package org.jkiss.dbeaver.ext.weaviate.ui.query;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenant;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantStatus;
import org.jkiss.dbeaver.ext.weaviate.ui.WeaviateNavigatorRefresh;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.ui.UIUtils;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;

/**
 * The "Tenant:" row above the query fields, shown only for multi-tenant collections.
 * <p>
 * Nothing is preselected on purpose. A query against the wrong tenant does not fail -- it returns
 * plausible rows belonging to somebody else -- so the choice has to be deliberate. For the same
 * reason the combo follows the spec rather than its own selection: the tenant is usually set from
 * the read-path dialog or the "Select Tenant..." command, neither of which this row can see.
 * <p>
 * The Activate button appears only for the one case it answers: the chosen tenant is asleep
 * <em>and</em> the collection will not wake it automatically, so every read is guaranteed to fail
 * until somebody acts. With auto-activation on, an inactive tenant is not a problem worth a button.
 */
public class WeaviateTenantRow {

    private static final Log log = Log.getLog(WeaviateTenantRow.class);

    private final WeaviateQueryPanelContext context;

    private Composite tenantRow;
    private Combo tenantCombo;
    private Button tenantActivateButton;

    /**
     * The tenants behind the combo items. Kept because an inactive tenant's item text carries its
     * state, so the item string never equals the bare name held in the spec.
     */
    private List<WeaviateTenant> tenantItems = Collections.emptyList();

    public WeaviateTenantRow(@NotNull WeaviateQueryPanelContext context) {
        this.context = context;
    }

    @NotNull
    public Composite createControls(@NotNull Composite parent) {
        tenantRow = new Composite(parent, SWT.NONE);
        GridLayout tenantLayout = new GridLayout(3, false);
        tenantLayout.marginWidth = 0;
        tenantLayout.marginHeight = 0;
        tenantRow.setLayout(tenantLayout);
        tenantRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label tenantLabel = new Label(tenantRow, SWT.NONE);
        tenantLabel.setText(WeaviateUIMessages.query_tenant);

        tenantCombo = new Combo(tenantRow, SWT.READ_ONLY);
        tenantCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tenantCombo.setToolTipText(WeaviateUIMessages.query_tenant_tip);
        tenantCombo.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                context.dismissBanner();
                WeaviateCollection collection = context.currentCollection();
                if (collection != null) {
                    collection.setQuerySpec(collection.getQuerySpec().withTenant(currentTenant()));
                }
                syncActivateButton();
                // Switching tenant changes the whole result set, so reload rather than making
                // the user press Run to see a different tenant's data.
                context.runQuery();
            }
        });

        tenantActivateButton = new Button(tenantRow, SWT.PUSH);
        tenantActivateButton.setText(WeaviateUIMessages.tenant_activate_now);
        tenantActivateButton.setToolTipText(WeaviateUIMessages.tenant_activate_now_tip);
        tenantActivateButton.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                activateCurrentTenant();
            }
        });

        setVisible(false);
        return tenantRow;
    }

    /**
     * Show the tenant picker only for multi-tenant collections, and preselect nothing so the
     * choice is deliberate.
     */
    public void refresh() {
        WeaviateCollection collection = context.currentCollection();
        if (collection == null) {
            log.debug("Tenant picker hidden: no Weaviate collection resolved for this result set");
            setVisible(false);
            return;
        }
        if (!collection.isMultiTenant()) {
            log.debug("Tenant picker hidden: " + collection.getName() + " is not multi-tenant");
            setVisible(false);
            return;
        }
        log.debug("Tenant picker shown for multi-tenant collection " + collection.getName());
        setVisible(true);
        // Follow the spec, not whatever the combo happened to show.
        String current = collection.getQuerySpec().getTenant();
        tenantCombo.removeAll();
        try {
            tenantItems = collection.listTenants(new VoidProgressMonitor());
        } catch (DBException e) {
            log.debug("Cannot list tenants", e);
            context.showError(e.getMessage());
            return;
        }
        for (WeaviateTenant tenant : tenantItems) {
            // An inactive tenant refuses every read, so picking one out of a list that looks
            // uniform ends in the server's own "tenant not active" with no hint of why.
            tenantCombo.add(tenant.isActive()
                ? tenant.name()
                : tenant.name() + "  (" + tenant.status().getLabel() + ")");
        }
        if (tenantCombo.getItemCount() == 0) {
            context.showError(WeaviateUIMessages.query_tenant_none);
            return;
        }
        int idx = indexOfTenant(current);
        if (idx >= 0) {
            tenantCombo.select(idx);
        } else {
            // Nothing chosen yet, or the stored tenant no longer exists -- leave it unselected
            // rather than silently pointing at a different tenant's data.
            tenantCombo.deselectAll();
        }
        syncActivateButton();
    }

    /**
     * Point the combo at the tenant the spec actually holds, without refetching the list.
     */
    public void syncSelection() {
        WeaviateCollection collection = context.currentCollection();
        if (collection == null || tenantCombo == null || tenantCombo.isDisposed()) {
            return;
        }
        String current = collection.getQuerySpec().getTenant();
        if (current == null) {
            return;
        }
        int idx = indexOfTenant(current);
        if (idx >= 0 && idx != tenantCombo.getSelectionIndex()) {
            tenantCombo.select(idx);
        }
    }

    /** The selected tenant's name, or null when none is chosen or the row is not shown. */
    @Nullable
    public String currentTenant() {
        if (tenantCombo == null || tenantCombo.isDisposed() || !tenantRow.isVisible()) {
            return null;
        }
        int idx = tenantCombo.getSelectionIndex();
        return idx < 0 || idx >= tenantItems.size() ? null : tenantItems.get(idx).name();
    }

    private void setVisible(boolean visible) {
        if (tenantRow == null || tenantRow.isDisposed()) {
            return;
        }
        tenantRow.setVisible(visible);
        ((GridData) tenantRow.getLayoutData()).exclude = !visible;
        tenantRow.getParent().layout(true, true);
    }

    /**
     * Shows the Activate button only when it is the answer -- see the class comment.
     */
    private void syncActivateButton() {
        if (tenantActivateButton == null || tenantActivateButton.isDisposed()) {
            return;
        }
        WeaviateCollection collection = context.currentCollection();
        int idx = tenantCombo.getSelectionIndex();
        boolean asleep = collection != null
            && idx >= 0 && idx < tenantItems.size()
            && !tenantItems.get(idx).isActive()
            && !Boolean.TRUE.equals(collection.getAutoTenantActivation());
        tenantActivateButton.setVisible(asleep);
        tenantActivateButton.setLayoutData(exclusion(!asleep));
        tenantRow.layout();
    }

    /** Layout data that removes the button from the row entirely when it is not needed. */
    private static GridData exclusion(boolean excluded) {
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.exclude = excluded;
        return gd;
    }

    /**
     * Activates the selected tenant and re-runs, since the query the user already asked for is
     * the reason they pressed it.
     */
    private void activateCurrentTenant() {
        WeaviateCollection collection = context.currentCollection();
        int idx = tenantCombo.getSelectionIndex();
        if (collection == null || idx < 0 || idx >= tenantItems.size()) {
            return;
        }
        WeaviateTenant tenant = tenantItems.get(idx);
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    collection.setTenantStatus(monitor, List.of(tenant), WeaviateTenantStatus.ACTIVE);
                    WeaviateNavigatorRefresh.afterTenantChange(monitor, collection);
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot activate tenant", e.getTargetException());
            context.showError(e.getTargetException().getMessage());
            return;
        } catch (InterruptedException e) {
            return;
        }
        context.dismissBanner();
        refresh();
        context.runQuery();
    }

    /**
     * Position of a tenant by name. Combo#indexOf cannot be used: an inactive tenant's item text
     * carries its state, so it never equals the bare name held in the spec.
     */
    private int indexOfTenant(@Nullable String name) {
        if (name == null) {
            return -1;
        }
        for (int i = 0; i < tenantItems.size(); i++) {
            if (tenantItems.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
