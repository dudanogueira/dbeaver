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
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.DBPImageProvider;
import org.jkiss.dbeaver.model.DBPStatefulObject;
import org.jkiss.dbeaver.model.navigator.DBNModel;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Confirms a change by listing the objects it will make, and the ones it will not.
 * <p>
 * Modelled on the platform's {@code NavigatorNodesDeletionConfirmations}, which is the dialog
 * Delete uses and the one to look like. It cannot be reused here, for two reasons in its
 * {@code createCustomArea}: the object table is built only when {@code selectedObjects.size() > 1},
 * so confirming a change to a single object shows a bare message naming it nowhere; and every row
 * must be a {@code DBNNode}, which rules out anything the navigator does not show.
 * <p>
 * The second limit is the one that matters here. These actions routinely leave part of a selection
 * alone -- a built-in role cannot be deleted, a user from the server's environment cannot be
 * changed, a user already deactivated has nothing to do -- and those exclusions belong in the same
 * grid as everything else, with a reason beside them. Saying "3 will be skipped" underneath a list
 * of five is strictly less clear than showing all five and marking which two.
 */
public class WeaviateChangeConfirmDialog extends BaseDialog {

    /**
     * One row of the grid: an object the action was asked about, or something it will affect.
     *
     * @param outcome       what will happen to it, in words: "will be deleted", "built-in role"
     * @param included      whether it is actually being changed; excluded rows are greyed
     * @param informational whether it is context rather than a target -- something the change
     *                      reaches without being applied to it, like a user who holds a role
     *                      about to be deleted. Counted separately, since "3 of 9 will be
     *                      changed" is a false statement when six of the nine were never
     *                      candidates.
     */
    public record Row(
        @Nullable DBPImage icon,
        @NotNull String name,
        @NotNull String type,
        @NotNull String outcome,
        boolean included,
        boolean informational
    ) {
        public Row(@Nullable DBPImage icon, @NotNull String name, @NotNull String type,
                   @NotNull String outcome, boolean included) {
            this(icon, name, type, outcome, included, false);
        }

        /** A row for something the change reaches rather than something it changes. */
        @NotNull
        public static Row affected(
            @Nullable DBPImage icon, @NotNull String name, @NotNull String type,
            @NotNull String outcome
        ) {
            return new Row(icon, name, type, outcome, false, true);
        }

        /**
         * A row for a navigator object, taking its icon and state overlay so it looks the way the
         * same object looks in the tree.
         */
        @NotNull
        public static Row of(
            @NotNull DBSObject object, @NotNull String type,
            @NotNull String outcome, boolean included
        ) {
            DBPImage image = object instanceof DBPImageProvider provider
                ? provider.getObjectImage() : DBIcon.TYPE_OBJECT;
            if (image != null && object instanceof DBPStatefulObject stateful) {
                image = DBNModel.getStateOverlayImage(image, stateful.getObjectState());
            }
            return new Row(image, object.getName(), type, outcome, included);
        }
    }

    private final String message;
    private final List<Row> rows;
    private final String confirmLabel;

    public WeaviateChangeConfirmDialog(
        @NotNull Shell shell,
        @NotNull String title,
        @NotNull String message,
        @NotNull List<Row> rows,
        @NotNull String confirmLabel
    ) {
        super(shell, title, DBIcon.STATUS_WARNING);
        this.message = message;
        this.rows = rows;
        this.confirmLabel = confirmLabel;
    }

    /** Whether anything would actually change; a dialog with nothing to do should not open. */
    public static boolean hasIncluded(@NotNull List<Row> rows) {
        return rows.stream().anyMatch(Row::included);
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

        Label headline = UIUtils.createLabel(group, message);
        headline.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        // VIRTUAL: "all users" on a large deployment is a legitimate action, and building a
        // TableItem per row up front would freeze the dialog before it opened.
        Table table = new Table(group,
            SWT.BORDER | SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.VIRTUAL);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        UIUtils.createTableColumn(table, SWT.LEFT, "Name");
        UIUtils.createTableColumn(table, SWT.LEFT, "Type");
        UIUtils.createTableColumn(table, SWT.LEFT, "Outcome");

        // A system colour rather than a literal, so it stays legible under a dark theme.
        Color greyed = table.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
        table.addListener(SWT.SetData, event -> {
            TableItem item = (TableItem) event.item;
            // event.index, not Table#indexOf: indexOf is a linear scan, which would make painting
            // quadratic and undo the point of a virtual table.
            Row row = rows.get(event.index);
            if (row.icon() != null) {
                item.setImage(DBeaverIcons.getImage(row.icon()));
            }
            item.setText(0, row.name());
            item.setText(1, row.type());
            item.setText(2, row.outcome());
            if (!row.included()) {
                // Greyed as well as worded: the eye finds the untouched rows before the text does.
                item.setForeground(greyed);
            }
        });
        table.setItemCount(rows.size());

        int em = UIUtils.getFontHeight(table);
        table.getColumn(0).setWidth(em * 20);
        table.getColumn(1).setWidth(em * 10);
        table.getColumn(2).setWidth(em * 26);
        GridData tableGd = new GridData(GridData.FILL_BOTH);
        tableGd.widthHint = em * 58;
        tableGd.heightHint = table.getHeaderHeight()
            + table.getItemHeight() * Math.min(12, Math.max(3, rows.size()));
        table.setLayoutData(tableGd);

        long candidates = rows.stream().filter(r -> !r.informational()).count();
        long included = rows.stream().filter(Row::included).count();
        long affected = rows.stream().filter(Row::informational).count();
        StringBuilder note = new StringBuilder();
        if (included < candidates) {
            note.append(MessageFormat.format(
                "{0} of {1} will be changed; the rest are listed above with the reason.",
                included, candidates));
        }
        if (affected > 0) {
            if (note.length() > 0) {
                note.append('\n');
            }
            note.append(MessageFormat.format(
                "{0} user(s) and group(s) hold what is being removed and are listed too. "
                    + "They are not changed themselves.", affected));
        }
        if (note.length() > 0) {
            Label label = UIUtils.createLabel(group, note.toString());
            label.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        }
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, confirmLabel, true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    /** Rows for the objects that will actually be changed, in the order they were given. */
    @NotNull
    public static List<Row> included(@NotNull List<Row> rows) {
        List<Row> result = new ArrayList<>();
        for (Row row : rows) {
            if (row.included()) {
                result.add(row);
            }
        }
        return result;
    }
}
