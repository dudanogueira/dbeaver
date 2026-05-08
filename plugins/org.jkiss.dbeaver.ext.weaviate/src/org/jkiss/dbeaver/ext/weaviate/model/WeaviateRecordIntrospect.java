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

import io.weaviate.client6.v1.internal.TaggedUnion;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

final class WeaviateRecordIntrospect {

    private WeaviateRecordIntrospect() {
    }

    /**
     * Flatten a Java record (or TaggedUnion) into a list of name/value display fields.
     * Nested records are recursively expanded with dot-notation names.
     * Lists, maps, and complex non-record types fall back to their toString(), and
     * null values are omitted.
     */
    @NotNull
    static List<WeaviateMetadataField> toFields(@NotNull DBSObject parent, @Nullable Object value) {
        List<WeaviateMetadataField> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        appendFields(result, parent, "", value);
        return result;
    }

    private static void appendFields(
        @NotNull List<WeaviateMetadataField> out,
        @NotNull DBSObject parent,
        @NotNull String prefix,
        @Nullable Object value
    ) {
        if (value == null) {
            return;
        }
        if (value instanceof TaggedUnion<?, ?> union) {
            Object kind = union._kind();
            if (kind != null) {
                out.add(new WeaviateMetadataField(parent, prefix.isEmpty() ? "kind" : prefix + ".kind",
                    kind.toString()));
            }
            Object self = union._self();
            if (self != null && self != union) {
                appendFields(out, parent, prefix, self);
            }
            return;
        }
        if (value.getClass().isRecord()) {
            for (RecordComponent rc : value.getClass().getRecordComponents()) {
                Object child;
                try {
                    child = rc.getAccessor().invoke(value);
                } catch (ReflectiveOperationException e) {
                    continue;
                }
                String fieldName = prefix.isEmpty() ? rc.getName() : prefix + "." + rc.getName();
                appendValue(out, parent, fieldName, child);
            }
            return;
        }
        // Top-level scalar without prefix is unusual; render as anonymous "value".
        appendValue(out, parent, prefix.isEmpty() ? "value" : prefix, value);
    }

    private static void appendValue(
        @NotNull List<WeaviateMetadataField> out,
        @NotNull DBSObject parent,
        @NotNull String name,
        @Nullable Object value
    ) {
        if (value == null) {
            return;
        }
        if (value instanceof TaggedUnion<?, ?> || value.getClass().isRecord()) {
            appendFields(out, parent, name, value);
            return;
        }
        if (value instanceof Collection<?> coll) {
            if (coll.isEmpty()) return;
            out.add(new WeaviateMetadataField(parent, name, formatCollection(coll)));
            return;
        }
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) return;
            out.add(new WeaviateMetadataField(parent, name, formatMap(map)));
            return;
        }
        out.add(new WeaviateMetadataField(parent, name, String.valueOf(value)));
    }

    private static String formatCollection(@NotNull Collection<?> coll) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Object o : coll) {
            if (!first) sb.append(", ");
            sb.append(o);
            first = false;
        }
        return sb.append("]").toString();
    }

    private static String formatMap(@NotNull Map<?, ?> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        return sb.append("}").toString();
    }
}
