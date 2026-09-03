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
package org.jkiss.dbeaver.ext.weaviate.edit;

import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateCollection;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateConfigDocument;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateConfigScript;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateConfigSetting;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateDataSource;
import org.jkiss.dbeaver.ext.weaviate.model.WeaviateSchemaRest;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.impl.edit.DBECommandAbstract;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A pending configuration change, queued rather than sent.
 * <p>
 * Going through DBeaver's command context rather than saving on the spot is what gives the
 * Configuration tab the things a settings screen should have: every pending change listed before
 * anything is sent, a script to look at first, Revert, and a dirty marker that stops the editor
 * closing on unsaved work. Several collections edited at once appear in one confirmation, which is
 * the behaviour asked for.
 * <p>
 * <b>The document is re-read at save time, not at edit time.</b> The form is populated from a
 * definition fetched when the tab opened, and between then and Save the collection may have been
 * changed by someone else -- or by another tab of the same editor. Applying the edited paths to a
 * freshly fetched document narrows that window to the width of the save itself, and means a change
 * to an unrelated section made elsewhere survives rather than being overwritten with what this tab
 * happened to be showing.
 */
public class WeaviateConfigUpdateCommand extends DBECommandAbstract<WeaviateCollection> {

    private final List<WeaviateConfigScript.Change> changes;

    public WeaviateConfigUpdateCommand(
        @NotNull WeaviateCollection collection,
        @NotNull List<WeaviateConfigScript.Change> changes
    ) {
        super(collection, title(collection, changes));
        this.changes = List.copyOf(changes);
    }

    @NotNull
    private static String title(
        @NotNull WeaviateCollection collection, @NotNull List<WeaviateConfigScript.Change> changes
    ) {
        if (changes.size() == 1) {
            return "Set " + changes.get(0).setting().getLabel() + " on " + collection.getName();
        }
        return "Change " + changes.size() + " settings on " + collection.getName();
    }

    @NotNull
    public List<WeaviateConfigScript.Change> getChanges() {
        return changes;
    }

    @NotNull
    @Override
    public DBEPersistAction[] getPersistActions(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext executionContext,
        @NotNull Map<String, Object> options
    ) {
        WeaviateCollection collection = getObject();
        String name = collection.getName();
        return new DBEPersistAction[]{
            new WeaviateEditAction(
                getTitle(),
                WeaviateConfigScript.render(name, changes),
                () -> {
                    if (!collection.isConfigEditable()) {
                        throw new DBException(
                            "Configuration cannot be saved on a connection using username/password"
                                + " (OIDC) authentication: the change goes over REST, which has no"
                                + " way to authenticate there. Use API key authentication instead.");
                    }
                    WeaviateDataSource dataSource = (WeaviateDataSource) collection.getDataSource();
                    WeaviateConfigDocument document =
                        WeaviateConfigDocument.of(collection.readRawDefinition());
                    for (WeaviateConfigScript.Change change : changes) {
                        apply(document, change);
                    }
                    WeaviateSchemaRest.updateCollection(dataSource, name, document.toJson());
                    // Read back rather than patching the local copy: the server is free to adjust
                    // what it stored, and the form must show what it actually holds. The same
                    // convention setAutoTenantOptions already follows.
                    collection.refreshConfig();
                })
        };
    }

    /**
     * Write one change into the document, in the type the server expects.
     * <p>
     * The form works in text, and JSON does not: sending {@code "0.5"} where a number belongs is
     * refused, and sending {@code "true"} for a boolean is worse -- accepted as a string by some
     * servers and quietly ignored. So the setting's declared kind decides the type here, at the
     * one place that knows both.
     */
    private static void apply(
        @NotNull WeaviateConfigDocument document, @NotNull WeaviateConfigScript.Change change
    ) {
        WeaviateConfigSetting setting = change.setting();
        String path = setting.pathIn(document, change.vectorName());
        String value = change.value();
        switch (setting.getKind()) {
            case QUANTIZER -> {
                // The choice picks the leaf: rq writes rq.enabled. Nothing is written for "none",
                // because there is no way back to uncompressed and the editor never offers one --
                // an unquantized collection can only move away from that state.
                if (!WeaviateConfigSetting.NO_QUANTIZER.equals(value)) {
                    document.setBoolean(path + "." + value + ".enabled", true);
                }
            }
            case INTEGER -> document.setNumber(path, (int) Double.parseDouble(value));
            case LONG -> document.setNumber(path, (long) Double.parseDouble(value));
            case DECIMAL -> document.setNumber(path, Double.parseDouble(value));
            case BOOLEAN -> document.setBoolean(path, Boolean.parseBoolean(value));
            case WORD_LIST -> document.setStrings(path, words(value));
            default -> document.setString(path, value);
        }
    }

    @NotNull
    private static List<String> words(@NotNull String commaSeparated) {
        List<String> words = new ArrayList<>();
        for (String word : commaSeparated.split(",")) {
            String trimmed = word.strip();
            if (!trimmed.isEmpty()) {
                words.add(trimmed);
            }
        }
        return words;
    }
}
