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

import java.util.Collections;
import java.util.List;

/**
 * How a property's analyzer splits a piece of text.
 * <p>
 * Two lists, not one. Weaviate reports the tokens it would write to the inverted index and the
 * tokens the same text would produce as a query, and models them separately even though every
 * standard tokenization returns them identical -- so they are kept apart here rather than
 * collapsed, which would quietly discard the distinction the server is drawing.
 * <p>
 * Plain JDK types only -- this crosses into the UI bundle, whose classloader cannot see the
 * shaded client.
 */
public final class WeaviateTokenPreview {

    private final String tokenization;
    private final List<String> indexed;
    private final List<String> query;

    public WeaviateTokenPreview(
        @Nullable String tokenization,
        @Nullable List<String> indexed,
        @Nullable List<String> query
    ) {
        this.tokenization = tokenization;
        this.indexed = indexed == null ? Collections.emptyList() : List.copyOf(indexed);
        this.query = query == null ? Collections.emptyList() : List.copyOf(query);
    }

    /**
     * The tokenization the server applied, or null.
     * <p>
     * Null on the per-property path: that response carries only the two token lists, since the
     * caller did not name a tokenizer and the server resolved it from the schema. The property's
     * own {@code getTokenization()} is the label to show in that case.
     */
    @Nullable
    public String getTokenization() {
        return tokenization;
    }

    /** Tokens written to the inverted index. Never null; empty when the text produced none. */
    @NotNull
    public List<String> getIndexed() {
        return indexed;
    }

    /** Tokens the same text produces when used as a query. */
    @NotNull
    public List<String> getQuery() {
        return query;
    }

    /**
     * Whether the two lists differ. They have matched for every standard tokenization observed, so
     * a difference is worth pointing at rather than leaving the reader to diff two columns.
     */
    public boolean indexedDiffersFromQuery() {
        return !indexed.equals(query);
    }

    @Override
    public String toString() {
        return indexed.size() + " indexed / " + query.size() + " query tokens";
    }
}
