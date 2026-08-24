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

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Parse a multi-vector (ColBERT-style) matrix: {@code [[0.1, 0.2], [0.3, 0.4]]}. The outer
     * brackets are optional, the inner ones are not -- without them there is nothing to say where
     * one row ends. Returns null on empty input.
     *
     * @throws NumberFormatException if a token isn't a finite float, a row is malformed, or the
     *                               rows are of differing lengths
     */
    @Nullable
    public static float[][] parseMulti(@Nullable String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        // Strip one outer bracket pair, but only when it wraps the whole matrix rather than being
        // a single row's own brackets. Told apart by what is left: a matrix still starts with a
        // row bracket afterwards, a lone flat vector has none left. Checking for a literal "[["
        // instead would reject "[ [1, 2], [3, 4] ]" over the space.
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            String unwrapped = trimmed.substring(1, trimmed.length() - 1).strip();
            if (unwrapped.startsWith("[")) {
                trimmed = unwrapped;
            }
        }
        List<float[]> rows = new ArrayList<>();
        int pos = 0;
        while (pos < trimmed.length()) {
            char c = trimmed.charAt(pos);
            if (c == ',' || Character.isWhitespace(c)) {
                pos++;
                continue;
            }
            if (c != '[') {
                throw new NumberFormatException(
                    "Expected '[' starting vector " + (rows.size() + 1) + ", found '" + c + "'");
            }
            int end = trimmed.indexOf(']', pos);
            if (end < 0) {
                throw new NumberFormatException("Unclosed vector " + (rows.size() + 1));
            }
            float[] row = parse(trimmed.substring(pos, end + 1));
            if (row == null) {
                throw new NumberFormatException("Empty vector at position " + (rows.size() + 1));
            }
            rows.add(row);
            pos = end + 1;
        }
        if (rows.isEmpty()) {
            return null;
        }
        // Weaviate rejects a ragged matrix anyway; catching it here names the offending row.
        int width = rows.get(0).length;
        for (int i = 1; i < rows.size(); i++) {
            if (rows.get(i).length != width) {
                throw new NumberFormatException("Vector " + (i + 1) + " has " + rows.get(i).length
                    + " components, expected " + width + " to match the first");
            }
        }
        return rows.toArray(new float[0][]);
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

    /**
     * Render a multi-vector matrix in the form {@link #parseMulti} accepts, so a value copied out
     * of the result grid can be pasted straight back into a Near Vector query.
     */
    @NotNull
    public static String formatMulti(@Nullable float[][] multiVector) {
        if (multiVector == null || multiVector.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < multiVector.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(format(multiVector[i]));
        }
        return sb.append("]").toString();
    }
}
