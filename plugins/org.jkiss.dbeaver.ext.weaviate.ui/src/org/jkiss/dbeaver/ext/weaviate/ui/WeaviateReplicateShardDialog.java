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
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateReplicationType;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;

import java.text.MessageFormat;
import java.util.List;

/**
 * Plans one replica movement: where a shard goes, and whether the source stays.
 * <p>
 * Collection, shard and source node all come from where the action was invoked, so the only
 * choices are the target and the type. The target list is the cluster's nodes <em>minus</em>
 * those already holding the shard: the server refuses that with
 * {@code shard already exist ... on target node}, so filtering here turns a rejection into a
 * choice that was never offered.
 */
public class WeaviateReplicateShardDialog extends BaseDialog {

    private final String collection;
    private final String shard;
    private final List<String> sources;
    private final List<String> candidates;
    private final int currentReplicas;

    private Combo sourceCombo;
    private String sourceNode;
    private Combo targetCombo;
    private Button copyButton;
    private Button moveButton;
    private Label consequence;

    private String targetNode;
    private WeaviateReplicationType type = WeaviateReplicationType.COPY;

    /**
     * @param sources    nodes that currently hold this shard; the movement comes from one of them
     * @param candidates nodes that do not, and so are legal targets
     */
    public WeaviateReplicateShardDialog(
        @NotNull Shell shell,
        @NotNull String collection,
        @NotNull String shard,
        @NotNull List<String> sources,
        @NotNull List<String> candidates
    ) {
        super(shell, "Move or copy a replica", DBIcon.TREE_PARTITION);
        this.collection = collection;
        this.shard = shard;
        this.sources = sources;
        this.candidates = candidates;
        this.currentReplicas = sources.size();
        this.sourceNode = sources.isEmpty() ? "" : sources.get(0);
    }

    @NotNull
    public String getSourceNode() {
        return sourceNode;
    }

    @Nullable
    public String getTargetNode() {
        return targetNode;
    }

    @NotNull
    public WeaviateReplicationType getReplicationType() {
        return type;
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

        Composite what = UIUtils.createComposite(group, 2);
        what.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        UIUtils.createLabel(what, "Collection");
        UIUtils.createLabel(what, collection);
        UIUtils.createLabel(what, "Shard");
        UIUtils.createLabel(what, shard);
        UIUtils.createLabel(what, "From");
        if (sources.size() > 1) {
            // A shard with several replicas has several legal sources, and which one is taken
            // matters for a move: that node is the one that ends up without a copy.
            sourceCombo = new Combo(what, SWT.READ_ONLY | SWT.BORDER);
            sourceCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            sources.forEach(sourceCombo::add);
            sourceCombo.select(0);
            sourceCombo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
                sourceNode = sources.get(Math.max(0, sourceCombo.getSelectionIndex()));
                select(type);
            }));
        } else {
            UIUtils.createLabel(what, sourceNode);
        }
        UIUtils.createLabel(what, "To");
        targetCombo = new Combo(what, SWT.READ_ONLY | SWT.BORDER);
        targetCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        candidates.forEach(targetCombo::add);
        if (!candidates.isEmpty()) {
            targetCombo.select(0);
        }

        UIUtils.createLabel(group, "");
        copyButton = UIUtils.createRadioButton(group, "Copy — "
            + WeaviateReplicationType.COPY.getExplanation(), WeaviateReplicationType.COPY,
            SelectionListener.widgetSelectedAdapter(e -> select(WeaviateReplicationType.COPY)));
        moveButton = UIUtils.createRadioButton(group, "Move — "
            + WeaviateReplicationType.MOVE.getExplanation(), WeaviateReplicationType.MOVE,
            SelectionListener.widgetSelectedAdapter(e -> select(WeaviateReplicationType.MOVE)));
        copyButton.setSelection(true);

        consequence = new Label(group, SWT.WRAP);
        GridData consequenceGd = new GridData(GridData.FILL_HORIZONTAL);
        consequenceGd.widthHint = UIUtils.getFontHeight(group) * 46;
        consequence.setLayoutData(consequenceGd);

        if (candidates.isEmpty()) {
            // Every node already holds this shard, so there is no legal target at all. Say it
            // here rather than letting the server refuse whatever gets chosen.
            Label none = UIUtils.createLabel(group,
                "Every node already holds a replica of this shard, so there is nowhere to send "
                    + "another. A replica can only move to a node that does not have one.");
            none.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        }
        select(WeaviateReplicationType.COPY);
        return area;
    }

    private void select(@NotNull WeaviateReplicationType chosen) {
        type = chosen;
        if (consequence == null || consequence.isDisposed()) {
            return;
        }
        String warning = chosen.getQuorumWarning(currentReplicas);
        if (warning != null) {
            consequence.setText(warning);
        } else if (chosen == WeaviateReplicationType.MOVE) {
            consequence.setText(MessageFormat.format(
                "This shard stays at {0} replica(s). The copy on {1} is removed once the target "
                    + "has caught up, which is the last step and cannot be cancelled.",
                currentReplicas, sourceNode));
        } else {
            consequence.setText(MessageFormat.format(
                "This shard goes from {0} to {1} replica(s).",
                currentReplicas, currentReplicas + 1));
        }
        consequence.getParent().layout(true, true);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Start", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        getButton(IDialogConstants.OK_ID).setEnabled(!candidates.isEmpty());
    }

    @Override
    protected void okPressed() {
        int index = targetCombo.getSelectionIndex();
        if (index < 0 || index >= candidates.size()) {
            return;
        }
        targetNode = candidates.get(index);
        if (sourceCombo != null && !sourceCombo.isDisposed()) {
            sourceNode = sources.get(Math.max(0, sourceCombo.getSelectionIndex()));
        }
        type = moveButton.getSelection() ? WeaviateReplicationType.MOVE : WeaviateReplicationType.COPY;
        super.okPressed();
    }
}
