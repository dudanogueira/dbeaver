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
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateShardStatus;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;

import java.text.MessageFormat;
import java.util.List;

/**
 * Lists every shard a status change is about to touch, and asks whether to go ahead.
 * <p>
 * This repeats what {@code NavigatorNodesDeletionConfirmations.confirm} does, which is normally
 * the wrong instinct -- the platform's dialog is the one Delete uses and it was deliberately
 * adopted here. It cannot be used for this, for two structural reasons, both in
 * {@code createCustomArea}:
 * <ul>
 *   <li>the object table is only built when {@code selectedObjects.size() > 1}, so changing a
 *       single shard showed a bare message box with the shard named nowhere;</li>
 *   <li>every row must be a {@code DBNNode} -- anything else is skipped by
 *       {@code if (!(obj instanceof DBNNode node)) continue;} -- and the shards of inactive
 *       tenants have no node, because the cluster API does not list them. Those are exactly the
 *       shards the user has just chosen to include, so they were counted in the message, changed
 *       on the server, and absent from the table.</li>
 * </ul>
 * Both are cases where the platform dialog would show less than the whole truth about what is
 * about to happen, which for a confirmation is the one thing it must not do.
 * <p>
 * The columns follow the platform's -- name, type, description -- so this reads as the same
 * dialog rather than a different one.
 */
public class WeaviateShardChangeDialog extends BaseDialog {

    /**
     * One shard about to change.
     *
     * @param inTree whether the navigator can show this shard; false for an inactive tenant's
     *               shard, which the cluster API omits
     */
    public record Row(
        @NotNull String shard,
        @NotNull String collection,
        @Nullable String currentStatus,
        boolean inTree
    ) {
    }

    private final List<Row> rows;
    private final WeaviateShardStatus target;

    public WeaviateShardChangeDialog(
        @NotNull Shell shell, @NotNull List<Row> rows, @NotNull WeaviateShardStatus target
    ) {
        super(shell, "Set shard status", DBIcon.STATUS_WARNING);
        this.rows = rows;
        this.target = target;
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

        long collections = rows.stream().map(Row::collection).distinct().count();
        long hidden = rows.stream().filter(r -> !r.inTree()).count();
        StringBuilder message = new StringBuilder(MessageFormat.format(
            "Set {0} shard(s) to {1}, across {2} collection(s)?",
            rows.size(), target.name(), collections));
        if (hidden > 0) {
            message.append(MessageFormat.format(
                "\n\n{0} of them belong to inactive tenants and are not shown in the tree.",
                hidden));
        }
        UIUtils.createLabel(group, message.toString());

        // VIRTUAL, and the row contents filled on demand. Setting every shard of a large
        // multi-tenant collection is a legitimate action here, and this list can then run to
        // thousands -- building that many TableItems up front freezes the dialog before it opens.
        Table table = new Table(group, SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.VIRTUAL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        UIUtils.createTableColumn(table, SWT.LEFT, "Shard");
        UIUtils.createTableColumn(table, SWT.LEFT, "Collection");
        UIUtils.createTableColumn(table, SWT.LEFT, "Change");
        table.addListener(SWT.SetData, event -> {
            TableItem item = (TableItem) event.item;
            Row row = rows.get(table.indexOf(item));
            DBPImage overlay =
                WeaviateShardStatus.fromName(row.currentStatus()).getObjectState().getOverlayImage();
            if (overlay != null) {
                item.setImage(DBeaverIcons.getImage(overlay));
            }
            item.setText(0, row.shard());
            item.setText(1, row.collection());
            String from = row.currentStatus() == null ? "not reported" : row.currentStatus();
            item.setText(2, row.inTree()
                ? from + " \u2192 " + target.name()
                : from + " \u2192 " + target.name() + "  (inactive tenant)");
        });
        table.setItemCount(rows.size());

        // Fixed widths rather than UIUtils.packColumns: packing calls TableColumn#pack, which
        // materialises every row and undoes the point of a virtual table.
        int em = UIUtils.getFontHeight(table);
        table.getColumn(0).setWidth(em * 20);
        table.getColumn(1).setWidth(em * 16);
        table.getColumn(2).setWidth(em * 22);

        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.widthHint = em * 44;
        gd.heightHint = table.getHeaderHeight()
            + table.getItemHeight() * Math.min(12, Math.max(3, rows.size()));
        table.setLayoutData(gd);
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID,
            "Set " + target.name(), true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }
}
