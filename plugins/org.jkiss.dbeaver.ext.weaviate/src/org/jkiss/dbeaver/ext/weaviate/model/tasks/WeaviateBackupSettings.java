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
package org.jkiss.dbeaver.ext.weaviate.model.tasks;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.model.task.DBTTaskSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a backup or restore task was asked to do.
 * <p>
 * Shared by both directions because they differ in almost nothing: a backend, an id, a set of
 * collections and a CPU share. Only the verb changes.
 * <p>
 * Notably absent are the {@code bucket} and {@code path} overrides the create API accepts. The
 * client sends no bucket or path on the status and cancel calls, so a backup created with an
 * override cannot afterwards be polled or cancelled -- offering the option would be offering a
 * way to start something the UI then loses track of.
 */
public class WeaviateBackupSettings implements DBTTaskSettings {

    /** What the server accepts as a backup id, from {@code usecases/backup/handler.go}. */
    public static final Pattern VALID_ID = Pattern.compile("^[a-z0-9_-]+$");

    private static final String PREF_DATASOURCE = "weaviate.backup.datasource";
    private static final String PREF_BACKEND = "weaviate.backup.backend";
    private static final String PREF_ID = "weaviate.backup.id";
    private static final String PREF_INCLUDE = "weaviate.backup.include";
    private static final String PREF_EXCLUDE = "weaviate.backup.exclude";
    private static final String PREF_CPU = "weaviate.backup.cpu";

    private String dataSourceId = "";
    private String backendId = "filesystem";
    private String backupId = "";
    private List<String> includeCollections = new ArrayList<>();
    private List<String> excludeCollections = new ArrayList<>();
    private Integer cpuPercentage;

    /**
     * Which connection this task runs against, by container id.
     * <p>
     * Carried explicitly rather than inferred. A task can be saved and run later, from the Tasks
     * view or a schedule, with no selection to read it from -- and picking "the first Weaviate
     * connection" would back up the wrong server the moment there are two.
     */
    @NotNull
    public String getDataSourceId() {
        return dataSourceId;
    }

    public void setDataSourceId(@NotNull String dataSourceId) {
        this.dataSourceId = dataSourceId;
    }

    @NotNull
    public String getBackendId() {
        return backendId;
    }

    public void setBackendId(@NotNull String backendId) {
        this.backendId = backendId;
    }

    @NotNull
    public String getBackupId() {
        return backupId;
    }

    public void setBackupId(@NotNull String backupId) {
        this.backupId = backupId;
    }

    @NotNull
    public List<String> getIncludeCollections() {
        return includeCollections;
    }

    public void setIncludeCollections(@NotNull List<String> includeCollections) {
        this.includeCollections = new ArrayList<>(includeCollections);
    }

    @NotNull
    public List<String> getExcludeCollections() {
        return excludeCollections;
    }

    public void setExcludeCollections(@NotNull List<String> excludeCollections) {
        this.excludeCollections = new ArrayList<>(excludeCollections);
    }

    @Nullable
    public Integer getCpuPercentage() {
        return cpuPercentage;
    }

    public void setCpuPercentage(@Nullable Integer cpuPercentage) {
        this.cpuPercentage = cpuPercentage;
    }

    /**
     * Coerces text into something the server will accept as an id, so the rule is enforced while
     * typing rather than by a 422 afterwards.
     */
    @NotNull
    public static String sanitizeId(@Nullable String text) {
        if (text == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder(text.length());
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            clean.append(Character.isLetterOrDigit(c) || c == '-' || c == '_' ? c : '-');
        }
        return clean.toString();
    }

    public void loadSettings(@NotNull DBPPreferenceStore store) {
        dataSourceId = orDefault(store.getString(PREF_DATASOURCE), "");
        backendId = orDefault(store.getString(PREF_BACKEND), "filesystem");
        backupId = orDefault(store.getString(PREF_ID), "");
        includeCollections = split(store.getString(PREF_INCLUDE));
        excludeCollections = split(store.getString(PREF_EXCLUDE));
        int cpu = store.getInt(PREF_CPU);
        cpuPercentage = cpu > 0 ? cpu : null;
    }

    public void saveSettings(@NotNull DBPPreferenceStore store) {
        store.setValue(PREF_DATASOURCE, dataSourceId);
        store.setValue(PREF_BACKEND, backendId);
        store.setValue(PREF_ID, backupId);
        store.setValue(PREF_INCLUDE, String.join(",", includeCollections));
        store.setValue(PREF_EXCLUDE, String.join(",", excludeCollections));
        store.setValue(PREF_CPU, cpuPercentage == null ? 0 : cpuPercentage);
    }

    @NotNull
    private static String orDefault(@Nullable String value, @NotNull String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    @NotNull
    private static List<String> split(@Nullable String value) {
        List<String> result = new ArrayList<>();
        if (value == null || value.isBlank()) {
            return result;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
