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

public final class WeaviateQuerySpec {

    public static final float DEFAULT_HYBRID_ALPHA = 0.5f;

    private final WeaviateQueryMode mode;
    private final String query;
    private final float[] vector;
    private final Float alpha;
    private final List<String> queryProperties;
    private final Float distance;
    private final WeaviateHybridFusion fusionType;

    public WeaviateQuerySpec(@NotNull Builder b) {
        this.mode = b.mode;
        this.query = b.query;
        this.vector = b.vector;
        this.alpha = b.alpha;
        this.queryProperties = b.queryProperties == null ? Collections.emptyList() : List.copyOf(b.queryProperties);
        this.distance = b.distance;
        this.fusionType = b.fusionType;
    }

    @NotNull
    public static Builder builder(@NotNull WeaviateQueryMode mode) {
        return new Builder(mode);
    }

    @NotNull
    public static WeaviateQuerySpec fetch() {
        return builder(WeaviateQueryMode.FETCH).build();
    }

    @NotNull
    public static WeaviateQuerySpec bm25(@NotNull String query) {
        return builder(WeaviateQueryMode.BM25).query(query).build();
    }

    @NotNull
    public static WeaviateQuerySpec nearText(@NotNull String query) {
        return builder(WeaviateQueryMode.NEAR_TEXT).query(query).build();
    }

    @NotNull
    public static WeaviateQuerySpec nearVector(@NotNull float[] vector) {
        return builder(WeaviateQueryMode.NEAR_VECTOR).vector(vector).build();
    }

    @NotNull
    public static WeaviateQuerySpec hybrid(@NotNull String query, float alpha) {
        return builder(WeaviateQueryMode.HYBRID).query(query).alpha(alpha).build();
    }

    @NotNull
    public WeaviateQueryMode getMode() {
        return mode;
    }

    @Nullable
    public String getQuery() {
        return query;
    }

    @Nullable
    public float[] getVector() {
        return vector;
    }

    @Nullable
    public Float getAlpha() {
        return alpha;
    }

    @NotNull
    public List<String> getQueryProperties() {
        return queryProperties;
    }

    @Nullable
    public Float getDistance() {
        return distance;
    }

    @Nullable
    public WeaviateHybridFusion getFusionType() {
        return fusionType;
    }

    public boolean rankedResults() {
        return mode != WeaviateQueryMode.FETCH;
    }

    public static final class Builder {
        private final WeaviateQueryMode mode;
        private String query;
        private float[] vector;
        private Float alpha;
        private List<String> queryProperties;
        private Float distance;
        private WeaviateHybridFusion fusionType;

        private Builder(@NotNull WeaviateQueryMode mode) {
            this.mode = mode;
        }

        public Builder query(@Nullable String query) {
            this.query = query;
            return this;
        }

        public Builder vector(@Nullable float[] vector) {
            this.vector = vector;
            return this;
        }

        public Builder alpha(@Nullable Float alpha) {
            this.alpha = alpha;
            return this;
        }

        public Builder queryProperties(@Nullable List<String> properties) {
            this.queryProperties = properties;
            return this;
        }

        public Builder distance(@Nullable Float distance) {
            this.distance = distance;
            return this;
        }

        public Builder fusionType(@Nullable WeaviateHybridFusion fusionType) {
            this.fusionType = fusionType;
            return this;
        }

        @NotNull
        public WeaviateQuerySpec build() {
            return new WeaviateQuerySpec(this);
        }
    }
}
