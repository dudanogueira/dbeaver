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
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenant;
import org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;

import java.text.MessageFormat;
import java.util.ArrayList;

/**
 * Picks one tenant from a collection's tenant list.
 * <p>
 * Built around a filter box rather than a plain list or combo: a collection can have thousands of
 * tenants, where scrolling is useless and the user almost always knows part of the name. The
 * filter is the shared {@link org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantFilter}, so
 * plain text is a substring match and a pattern such as {@code acme-eu-*} is a glob -- the same
 * rule as the Manage Tenants dialog, since two search boxes over the same list that disagree
 * about what a star means would be worse than either rule alone.
 * <p>
 * Inactive tenants are labelled as such. They can still be picked -- the choice is the user's,
 * and a tenant may be about to be activated -- but choosing one blindly ends in the server's own
 * "tenant not active", which arrives long after the decision and explains nothing about how to
 * fix it.
 */
public class WeaviateTenantSelectDialog extends BaseDialog {

    private final java.util.List<WeaviateTenant> allTenants;
    private final String collectionName;
    private final String initialSelection;

    private Text filterText;
    private List tenantList;
    private Label countLabel;
    private java.util.List<WeaviateTenant> visible = new ArrayList<>();
    private String selected;

    public WeaviateTenantSelectDialog(
        @NotNull Shell shell,
        @NotNull String collectionName,
        @NotNull java.util.List<WeaviateTenant> tenants,
        @Nullable String initialSelection
    ) {
        super(shell, MessageFormat.format(WeaviateUIMessages.tenant_dialog_title, collectionName), null);
        this.collectionName = collectionName;
        this.allTenants = tenants;
        this.initialSelection = initialSelection;
    }

    @Nullable
    public String getSelectedTenant() {
        return selected;
    }

    @Override
    protected Composite createDialogArea(Composite parent) {
        Composite area = super.createDialogArea(parent);
        Composite group = UIUtils.createComposite(area, 1);
        group.setLayoutData(new GridData(GridData.FILL_BOTH));

        UIUtils.createLabel(group, MessageFormat.format(
            WeaviateUIMessages.tenant_dialog_prompt, collectionName));

        filterText = new Text(group, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH | SWT.ICON_CANCEL);
        filterText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        filterText.setMessage(WeaviateUIMessages.tenant_dialog_filter_hint);
        filterText.addModifyListener(e -> applyFilter());

        tenantList = new List(group, SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        GridData listGd = new GridData(GridData.FILL_BOTH);
        listGd.heightHint = 260;
        listGd.widthHint = 380;
        tenantList.setLayoutData(listGd);
        tenantList.addSelectionListener(org.eclipse.swt.events.SelectionListener.widgetSelectedAdapter(e -> updateOk()));
        // Double-click accepts, which is what people do instinctively in a picker this size.
        tenantList.addListener(SWT.DefaultSelection, e -> {
            if (tenantList.getSelectionIndex() >= 0) {
                okPressed();
            }
        });

        countLabel = new Label(group, SWT.NONE);
        countLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        applyFilter();
        if (initialSelection != null) {
            for (int i = 0; i < visible.size(); i++) {
                if (visible.get(i).name().equals(initialSelection)) {
                    tenantList.select(i);
                    break;
                }
            }
        }
        return area;
    }

    @Override
    protected Control createContents(Composite parent) {
        Control contents = super.createContents(parent);
        updateOk();
        return contents;
    }

    private void applyFilter() {
        visible = org.jkiss.dbeaver.ext.weaviate.model.WeaviateTenantFilter
            .of(filterText == null ? "" : filterText.getText())
            .filter(allTenants);
        String[] labels = new String[visible.size()];
        for (int i = 0; i < visible.size(); i++) {
            WeaviateTenant tenant = visible.get(i);
            // The state goes in the label rather than a second column, because SWT's List has no
            // columns and the alternative -- a Table -- would be a heavier control for a picker.
            labels[i] = tenant.isActive()
                ? tenant.name()
                : tenant.name() + "  (" + tenant.status().getLabel() + ")";
        }
        tenantList.setItems(labels);
        countLabel.setText(MessageFormat.format(
            WeaviateUIMessages.tenant_dialog_count, visible.size(), allTenants.size()));
        if (visible.size() == 1) {
            tenantList.select(0);
        }
        updateOk();
    }

    private void updateOk() {
        var ok = getButton(IDialogConstants.OK_ID);
        if (ok != null && !ok.isDisposed()) {
            ok.setEnabled(tenantList != null && tenantList.getSelectionIndex() >= 0);
        }
    }

    @Override
    protected void okPressed() {
        int idx = tenantList.getSelectionIndex();
        selected = idx < 0 ? null : visible.get(idx).name();
        super.okPressed();
    }
}
