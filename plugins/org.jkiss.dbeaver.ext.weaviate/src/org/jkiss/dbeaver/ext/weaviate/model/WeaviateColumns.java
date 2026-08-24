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

public final class WeaviateColumns {

    public static final String UUID = "uuid";
    public static final String SCORE = "_score";
    public static final String DISTANCE = "_distance";
    /** Server's explanation of how {@link #SCORE} was arrived at. Keyword modes only. */
    public static final String EXPLAIN_SCORE = "_explainScore";

    /** Object creation time, rendered ISO-8601. Opt-in via the metadata checkboxes. */
    public static final String CREATED = "_created";
    /** Last update time, rendered ISO-8601. Opt-in via the metadata checkboxes. */
    public static final String UPDATED = "_updated";
    /**
     * Certainty: the vector distance normalised to [0, 1]. Near_* modes only -- keyword and
     * fused scores have no distance to derive it from.
     */
    public static final String CERTAINTY = "_certainty";

    /** Per-object generated text from a single-prompt generative task. */
    public static final String GENERATED = "_generated";
    /** Provider usage metadata (token counts) for the generated text. Opt-in. */
    public static final String GENERATIVE_META = "_generativeMeta";

    /** Column holding the embedding when a collection has a single (or unnamed) vector. */
    public static final String VECTOR = "_vector";
    /** Prefix for per-vector columns when a collection declares several named vectors. */
    public static final String VECTOR_PREFIX = "_vector_";

    /**
     * Grid column name for the named vector {@code vectorName}.
     *
     * @param singleVector whether the collection declares at most one vector, in which case the
     *                     plain {@link #VECTOR} name is used instead of a suffixed one
     */
    public static String vectorColumn(String vectorName, boolean singleVector) {
        return singleVector ? VECTOR : VECTOR_PREFIX + vectorName;
    }

    /**
     * Named vector behind a {@link #vectorColumn} name, or {@code null} if not a vector column.
     */
    public static String vectorNameOf(String column, String defaultVectorName) {
        if (VECTOR.equals(column)) {
            return defaultVectorName;
        }
        if (column != null && column.startsWith(VECTOR_PREFIX)) {
            return column.substring(VECTOR_PREFIX.length());
        }
        return null;
    }

    private WeaviateColumns() {
    }
}
