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
package org.jkiss.dbeaver.ext.weaviate.ui.config;

import org.eclipse.jface.action.IContributionManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ext.weaviate.edit.WeaviateConfigUpdateCommand;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateConfigDocument;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateConfigScript;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateConfigSetting;
import org.jkiss.dbeaver.model.edit.DBECommand;
import org.jkiss.dbeaver.model.edit.DBECommandReflector;
import org.jkiss.dbeaver.model.navigator.DBNEvent;
import org.jkiss.dbeaver.ui.UIUtils;
import org.eclipse.ui.IWorkbenchSite;
import org.jkiss.dbeaver.ui.IRefreshablePart;
import org.jkiss.dbeaver.ui.controls.ProgressPageControl;
import org.jkiss.dbeaver.ui.editors.DatabaseEditorUtils;
import org.jkiss.dbeaver.ui.editors.AbstractDatabaseObjectEditor;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Configuration tab: the settings of a collection that a running server will accept a change
 * to.
 * <p>
 * Every control here corresponds to an entry in {@link WeaviateConfigSetting}, which was built by
 * measurement rather than from the documentation -- and the absences matter as much as the
 * presences. Eighteen collection settings are refused by the server and two more are accepted and
 * silently ignored; none of them has a control, because a control for a change that cannot happen
 * is worse than no control at all. Those settings remain visible, read-only, in the tree.
 * <p>
 * Changes are queued as a {@link WeaviateConfigUpdateCommand} rather than sent on the spot. That is
 * what gives this tab a Save that lists every pending change across every open editor, a script to
 * look at before sending, Revert, and a dirty marker -- none of which a form with its own Save
 * button would have.
 * <p>
 * The vector index section repeats per vector: a collection may have several, each with its own
 * index, and a change to one is not a change to the others.
 */
public class WeaviateConfigEditor extends AbstractDatabaseObjectEditor<WeaviateCollection> {

    private static final Log log = Log.getLog(WeaviateConfigEditor.class);

    /** A control bound to one setting of one vector; the vector name is empty off the vector group. */
    private record Bound(WeaviateConfigSetting setting, String vectorName, Control control) {
    }

    private PageControl pageControl;
    private Composite form;
    private final List<Bound> bound = new ArrayList<>();
    private final Map<String, String> loaded = new LinkedHashMap<>();
    private WeaviateConfigDocument document;
    private boolean populating;

    /**
     * A tab inside the entity editor, wrapped the way {@code PostgreScheduleEditor} wraps its own.
     * <p>
     * The wrapper is not decoration. Save and Revert are contributed by the tab through
     * {@code DatabaseEditorUtils.contributeStandardEditorActions}, so a folder editor that just
     * puts a composite on the parent gets no Save button of its own -- what appears then depends
     * on the surrounding editor, which is why it showed sometimes and not others.
     */
    @Override
    public void createPartControl(Composite parent) {
        pageControl = new PageControl(parent);
        form = new Composite(pageControl, SWT.NONE);
        form.setLayout(new GridLayout(1, false));
        form.setLayoutData(new GridData(GridData.FILL_BOTH));
        pageControl.createOrSubstituteProgressPanel(getSite());
    }

    private class PageControl extends ProgressPageControl {
        PageControl(@NotNull Composite parent) {
            super(parent, SWT.SHEET);
        }

        @Override
        public void fillCustomActions(@NotNull IContributionManager manager) {
            super.fillCustomActions(manager);
            IWorkbenchSite site = getSite();
            if (site != null) {
                manager.add(new Separator());
                DatabaseEditorUtils.contributeStandardEditorActions(site, manager);
            }
        }
    }

    @Override
    public void activatePart() {
        if (document == null) {
            reload();
        }
    }

    @Override
    public void setFocus() {
        if (form != null && !form.isDisposed()) {
            form.setFocus();
        }
    }

    @Override
    public RefreshResult refreshPart(Object source, boolean force) {
        if (force || document == null
            || (source instanceof DBNEvent event && event.getAction() == DBNEvent.Action.UPDATE)
        ) {
            reload();
            return RefreshResult.REFRESHED;
        }
        return RefreshResult.IGNORED;
    }

    /**
     * Re-read the definition and rebuild the form from it.
     * <p>
     * A rebuild rather than a repopulate: a collection can gain or lose a named vector, and the
     * vector sections are one per vector.
     */
    private void reload() {
        WeaviateCollection collection = getDatabaseObject();
        if (collection == null || form == null || form.isDisposed()) {
            return;
        }
        WeaviateConfigDocument[] holder = new WeaviateConfigDocument[1];
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    holder[0] = WeaviateConfigDocument.of(collection.readRawDefinition());
                } catch (DBException e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            log.error("Cannot read the definition of " + collection.getName(), e.getTargetException());
            showFailure(e.getTargetException().getMessage());
            return;
        } catch (InterruptedException e) {
            return;
        }
        document = holder[0];
        lastCommand = null;
        build(collection);
    }

    private void showFailure(@Nullable String message) {
        clear();
        Label label = new Label(form, SWT.WRAP);
        label.setText("Cannot read this collection's definition."
            + (message == null ? "" : "\n\n" + message));
        label.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        form.layout(true, true);
    }

    private void clear() {
        bound.clear();
        loaded.clear();
        for (Control child : form.getChildren()) {
            child.dispose();
        }
    }

    private void build(@NotNull WeaviateCollection collection) {
        populating = true;
        try {
            clear();
            if (!collection.isConfigEditable()) {
                // The same advisory RBAC and replication show, and for the same reason: a REST call
                // cannot be authenticated on an OIDC connection. Shown once, at the top, rather
                // than as a disabled state on every control.
                Label advice = new Label(form, SWT.WRAP);
                advice.setText("This connection authenticates with username/password (OIDC)."
                    + " Configuration is shown but cannot be saved: the change goes over REST,"
                    + " which has no way to authenticate here. Use an API key to edit it.");
                advice.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            }
            for (WeaviateConfigSetting.Group group : WeaviateConfigSetting.Group.values()) {
                if (group.isPerVector()) {
                    for (String vector : document.getVectorNames()) {
                        buildGroup(collection, group, vector);
                    }
                } else {
                    buildGroup(collection, group, "");
                }
            }
            form.layout(true, true);
        } finally {
            populating = false;
        }
    }

    private void buildGroup(
        @NotNull WeaviateCollection collection,
        @NotNull WeaviateConfigSetting.Group group,
        @NotNull String vector
    ) {
        List<WeaviateConfigSetting> settings = WeaviateConfigSetting.of(group);
        if (settings.isEmpty()) {
            return;
        }
        Group box = new Group(form, SWT.NONE);
        box.setText(group.isPerVector() && !vector.isEmpty()
            ? group.getLabel() + " \u2014 " + vector
            : group.getLabel());
        box.setLayout(new GridLayout(2, false));
        box.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        for (WeaviateConfigSetting setting : settings) {
            String path = setting.pathIn(document, vector);
            String current = valueAt(setting, path, vector);
            loaded.put(key(setting, vector), current);
            UIUtils.createLabel(box, setting.getLabel());
            Control control = createControl(box, setting, current);
            control.setToolTipText(setting.getTip());
            control.setEnabled(collection.isConfigEditable());
            bound.add(new Bound(setting, vector, control));
        }
    }

    @NotNull
    private String valueAt(
        @NotNull WeaviateConfigSetting setting, @NotNull String path, @NotNull String vector
    ) {
        return switch (setting.getKind()) {
            case QUANTIZER -> WeaviateConfigSetting.quantizerIn(document, vector);
            case BOOLEAN -> String.valueOf(Boolean.TRUE.equals(document.getBoolean(path)));
            case WORD_LIST -> String.join(", ", document.getStrings(path));
            case INTEGER, LONG -> {
                Double number = document.getNumber(path);
                yield number == null ? "" : String.valueOf(number.longValue());
            }
            case DECIMAL -> {
                Double number = document.getNumber(path);
                yield number == null ? "" : trimZero(number);
            }
            default -> {
                String text = document.getString(path);
                yield text == null ? "" : text;
            }
        };
    }

    @NotNull
    private static String trimZero(double value) {
        String text = String.valueOf(value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    @NotNull
    private Control createControl(
        @NotNull Composite parent, @NotNull WeaviateConfigSetting setting, @NotNull String current
    ) {
        if (setting.getKind() == WeaviateConfigSetting.Kind.BOOLEAN) {
            Button check = new Button(parent, SWT.CHECK);
            check.setSelection(Boolean.parseBoolean(current));
            check.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> onEdit()));
            return check;
        }
        if (setting.getKind() == WeaviateConfigSetting.Kind.QUANTIZER) {
            return createQuantizerControl(parent, setting, current);
        }
        if (setting.getKind() == WeaviateConfigSetting.Kind.CHOICE) {
            Combo combo = new Combo(parent, SWT.READ_ONLY | SWT.BORDER);
            List<String> choices = new ArrayList<>(setting.getChoices());
            if (!current.isEmpty() && !choices.contains(current)) {
                // The server is allowed to hold a value this build has never heard of, and a combo
                // that silently reset it to its first entry would change a setting nobody touched.
                choices.add(0, current);
            }
            choices.forEach(combo::add);
            combo.select(Math.max(0, choices.indexOf(current)));
            combo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            combo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> onEdit()));
            return combo;
        }
        Text text = new Text(parent, SWT.BORDER);
        text.setText(current);
        text.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        if (current.isEmpty()) {
            // Absent is not zero. The server omits some settings until they are set, and a blank
            // field says that where a fabricated default would invite a change to something that
            // is not there.
            text.setMessage("(not set)");
        }
        text.addModifyListener(e -> onEdit());
        return text;
    }

    /**
     * Queue the current state of the form as one command, replacing whatever was queued before.
     * <p>
     * One command for the whole tab rather than one per keystroke: a command per field would fill
     * the save confirmation with a line for every character typed. Replacing it means the queue
     * always holds exactly what the form shows, so Revert is the platform's own undo rather than
     * anything written here.
     */
    private void onEdit() {
        if (populating) {
            return;
        }
        if (getDatabaseObject() == null) {
            say("No collection bound: changes cannot be queued.");
            return;
        }
        try {
            queue();
        } catch (RuntimeException e) {
            // Queueing runs from an SWT listener, where a thrown exception reaches the display's
            // handler and is logged out of sight. Silence there looks exactly like a form that
            // decided nothing had changed, which is the worst way to lose an edit.
            log.error("Cannot queue the configuration change", e);
            say("Cannot queue this change: " + e);
        }
    }

    /**
     * What the tab believes is pending, on its own status line.
     * <p>
     * Save lives on the workbench and lights up from the command queue, several objects away from
     * here. When it does not light up, this line is what says whether the queue was the problem or
     * the form was.
     */
    private void say(@NotNull String message) {
        if (pageControl != null && !pageControl.isDisposed()) {
            pageControl.setInfo(message);
        }
    }

    private void queue() {
        List<WeaviateConfigScript.Change> changes = collectChanges();
        if (lastCommand != null) {
            try {
                removeChangeCommand(lastCommand);
            } catch (RuntimeException e) {
                // The queue is emptied by a save and by a revert, and neither tells this editor.
                // A command that has already left it is not an error, and must not stop the next
                // edit from queueing -- which is what made the Save button stop appearing after
                // the first save.
                log.debug("Previous configuration change was already off the queue", e);
            }
            lastCommand = null;
        }
        if (changes.isEmpty()) {
            // Back to what the server holds. Tell the workbench, or Save stays lit with nothing
            // behind it.
            firePropertyChange(PROP_DIRTY);
            say("No changes.");
            return;
        }
        lastCommand = new WeaviateConfigUpdateCommand(getDatabaseObject(), changes);
        addChangeCommand(lastCommand, new DBECommandReflector<>() {
            @Override
            public void redoCommand(@NotNull DBECommand<WeaviateCollection> command) {
            }

            @Override
            public void undoCommand(@NotNull DBECommand<WeaviateCollection> command) {
                // Revert throws the queue away; the form is rebuilt from the server rather than
                // unwound field by field, which is the only version that cannot drift.
                reload();
            }
        });
        // AbstractDatabaseObjectEditor.addChangeCommand does not fire this -- the version that did
        // is commented out in that class -- so without it the command sits on the queue and the
        // workbench never learns the editor is dirty. No dirty marker, and no Save.
        firePropertyChange(PROP_DIRTY);
        say(changes.size() == 1
            ? "1 pending change. Save to send it."
            : changes.size() + " pending changes. Save to send them.");
    }

    /**
     * The change queued for this tab, or null when the form matches the server.
     * <p>
     * Cleared whenever the form is rebuilt, because a save or a revert empties the queue without
     * telling the editor, and holding a reference to a command that has left it is how the next
     * edit fails to queue.
     */
    private WeaviateConfigUpdateCommand lastCommand;

    @NotNull
    private List<WeaviateConfigScript.Change> collectChanges() {
        List<WeaviateConfigScript.Change> changes = new ArrayList<>();
        for (Bound entry : bound) {
            String now = read(entry);
            String before = loaded.get(key(entry.setting(), entry.vectorName()));
            if (now == null || now.equals(before)) {
                continue;
            }
            changes.add(new WeaviateConfigScript.Change(entry.setting(), entry.vectorName(), now));
        }
        return changes;
    }

    /** @return the control's value, or null when it holds nothing to send */
    @Nullable
    private static String read(@NotNull Bound entry) {
        Control control = entry.control();
        if (control.isDisposed()) {
            return null;
        }
        if (control instanceof Button check) {
            return String.valueOf(check.getSelection());
        }
        if (control instanceof Combo combo) {
            return combo.getText();
        }
        if (!(control instanceof Text)) {
            // A setting shown but not offered: a quantizer that is already set is a Label, because
            // the server has no way back to uncompressed. Casting it blindly threw from inside an
            // SWT listener, where the exception is logged out of sight -- so on any collection with
            // a quantizer, every edit anywhere on the form died before it could be queued, and the
            // Save button never lit for anything.
            return null;
        }
        String text = ((Text) control).getText().strip();
        // A field emptied by hand is not a request to delete the setting -- there is no way to
        // express that here, and the server would take the absence as "leave it alone" anyway.
        return text.isEmpty() ? null : text;
    }

    /**
     * The quantizer picker, which is not a combo like the others because the change is one-way.
     * <p>
     * A collection that already has one shows it and offers nothing: the server has no way back to
     * uncompressed, and swapping one quantizer for another is the same door in a different colour.
     * A collection that has none offers all four, and asks before queueing the change rather than
     * only at Save -- the save confirmation lists it beside settings that are all reversible, and
     * this one is not.
     */
    @NotNull
    private Control createQuantizerControl(
        @NotNull Composite parent, @NotNull WeaviateConfigSetting setting, @NotNull String current
    ) {
        if (!WeaviateConfigSetting.NO_QUANTIZER.equals(current)) {
            Label fixed = new Label(parent, SWT.NONE);
            fixed.setText(current + "  (cannot be changed or removed)");
            fixed.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
            return fixed;
        }
        Combo combo = new Combo(parent, SWT.READ_ONLY | SWT.BORDER);
        setting.getChoices().forEach(combo::add);
        combo.select(0);
        combo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        combo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            String chosen = combo.getText();
            if (!WeaviateConfigSetting.NO_QUANTIZER.equals(chosen) && !confirmQuantizer(chosen)) {
                combo.select(0);
                return;
            }
            onEdit();
        }));
        return combo;
    }

    private boolean confirmQuantizer(@NotNull String quantizer) {
        return UIUtils.confirmAction(
            form.getShell(),
            "Enable " + quantizer.toUpperCase(java.util.Locale.ROOT) + " quantization",
            "Compressing this collection's vectors cannot be undone. Weaviate offers no way back "
                + "to uncompressed storage, so the only way out is to rebuild the collection and "
                + "reload its data.\n\nEnable " + quantizer.toUpperCase(java.util.Locale.ROOT)
                + " on save?");
    }

    @NotNull
    private static String key(@NotNull WeaviateConfigSetting setting, @NotNull String vector) {
        return vector + "/" + setting.name();
    }
}
