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
package org.jkiss.dbeaver.ext.weaviate.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.Map;
import java.util.TreeMap;

public class WeaviateModule implements DBSObject {

    private final WeaviateDataSource dataSource;
    private final String moduleId;
    private final Object rawValue;

    public WeaviateModule(@NotNull WeaviateDataSource dataSource, @NotNull String moduleId, @Nullable Object rawValue) {
        this.dataSource = dataSource;
        this.moduleId = moduleId;
        this.rawValue = rawValue;
    }

    @NotNull
    @Override
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 1)
    public String getName() {
        return moduleId;
    }

    @Nullable
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 2)
    public String getDocumentationHref() {
        Map<String, Object> map = asMap();
        if (map == null) return null;
        Object href = map.get("documentationHref");
        return href == null ? null : href.toString();
    }

    @Nullable
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 3, length = org.jkiss.dbeaver.model.meta.PropertyLength.MULTILINE)
    public String getAttributes() {
        Map<String, Object> map = asMap();
        if (map == null) {
            return rawValue == null ? null : rawValue.toString();
        }
        Map<String, Object> sorted = new TreeMap<>(map);
        sorted.remove("documentationHref");
        if (sorted.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(e.getKey()).append(" = ").append(e.getValue());
        }
        return sb.toString();
    }

    @Nullable
    private Map<String, Object> asMap() {
        if (rawValue instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) raw;
            return typed;
        }
        return null;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return dataSource;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public boolean isPersisted() {
        return true;
    }
}
