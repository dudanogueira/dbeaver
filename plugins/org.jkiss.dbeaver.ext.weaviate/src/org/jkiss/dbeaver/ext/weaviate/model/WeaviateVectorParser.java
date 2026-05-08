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

public final class WeaviateVectorParser {

    private WeaviateVectorParser() {
    }

    /**
     * Parse a comma-separated vector. Brackets are optional. Whitespace is ignored.
     * Returns null on empty input.
     *
     * @throws NumberFormatException if any token isn't a finite float.
     */
    @Nullable
    public static float[] parse(@Nullable String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.startsWith("[")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("]")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        trimmed = trimmed.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] tokens = trimmed.split(",");
        float[] out = new float[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i].strip();
            if (t.isEmpty()) {
                throw new NumberFormatException("Empty vector component at position " + i);
            }
            float f = Float.parseFloat(t);
            if (!Float.isFinite(f)) {
                throw new NumberFormatException("Non-finite vector component at position " + i + ": " + t);
            }
            out[i] = f;
        }
        return out;
    }

    @NotNull
    public static String format(@Nullable float[] vector) {
        if (vector == null || vector.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
