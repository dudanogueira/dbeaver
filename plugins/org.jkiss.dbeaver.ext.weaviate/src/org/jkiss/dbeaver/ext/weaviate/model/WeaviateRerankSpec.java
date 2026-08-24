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

import java.util.Objects;

/**
 * A rerank request: which property to hand the reranker, and optionally a query to rank against
 * instead of the search query itself.
 * <p>
 * Plain JDK types only -- this crosses into the UI bundle. Translated to the client's
 * {@code Rerank} record at dispatch, inside {@code WeaviateCollection}.
 */
public final class WeaviateRerankSpec {

    private final String property;
    private final String query;

    public WeaviateRerankSpec(@NotNull String property, @Nullable String query) {
        if (property.isBlank()) {
            throw new IllegalArgumentException("Rerank needs a property to rank on");
        }
        this.property = property;
        this.query = query == null || query.isBlank() ? null : query;
    }

    @NotNull
    public String getProperty() {
        return property;
    }

    /** Query to rank against, or null to let the server use the search query. */
    @Nullable
    public String getQuery() {
        return query;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WeaviateRerankSpec other
            && property.equals(other.property)
            && Objects.equals(query, other.query);
    }

    @Override
    public int hashCode() {
        return Objects.hash(property, query);
    }

    @Override
    public String toString() {
        return query == null ? property : property + ":" + query;
    }
}
