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
    /** Reference object UUID for {@link WeaviateQueryMode#NEAR_OBJECT}. */
    private final String objectId;
    /** Tenant to scope the query to; only meaningful for multi-tenant collections. */
    private final String tenant;
    /** Autocut groups, or null/0 for off. See {@link #getAutoCut()}. */
    private final Integer autoCut;
    /** Whether to ask for and show the score explanation. Off unless requested. */
    private final boolean explainScore;
    private final Float alpha;
    private final List<String> queryProperties;
    private final Float distance;
    private final WeaviateHybridFusion fusionType;
    private final List<WeaviateFilterRow> filterRows;
    private final boolean anyFilter;
    private final boolean includeVector;
    /** Opt-in metadata columns; each adds a request field and a grid column. */
    private final boolean withCreated;
    private final boolean withUpdated;
    private final boolean withCertainty;
    /** Named vectors to search, with their weights. Empty means "let the server pick". */
    private final List<WeaviateVectorTarget> targets;
    /** How several targets are joined into one ranking; null when there is only one. */
    private final WeaviateVectorCombination combination;

    public WeaviateQuerySpec(@NotNull Builder b) {
        this.mode = b.mode;
        this.query = b.query;
        this.vector = b.vector;
        this.objectId = b.objectId;
        this.tenant = b.tenant;
        this.autoCut = b.autoCut;
        this.explainScore = b.explainScore;
        this.alpha = b.alpha;
        this.queryProperties = b.queryProperties == null ? Collections.emptyList() : List.copyOf(b.queryProperties);
        this.distance = b.distance;
        this.fusionType = b.fusionType;
        this.filterRows = b.filterRows == null ? Collections.emptyList() : List.copyOf(b.filterRows);
        this.anyFilter = b.anyFilter;
        this.includeVector = b.includeVector;
        this.withCreated = b.withCreated;
        this.withUpdated = b.withUpdated;
        this.withCertainty = b.withCertainty;
        this.targets = b.targets == null ? Collections.emptyList() : List.copyOf(b.targets);
        this.combination = b.combination;
    }

    /**
     * Whether to fetch embeddings alongside the objects.
     * <p>
     * Note this builder defaults to {@code false}; a spec built for a real read is seeded from the
     * connection setting instead (see {@code WeaviateDataSource#getQuerySpec}), which is on unless
     * the user turned it off.
     */
    public boolean isIncludeVector() {
        return includeVector;
    }

    /**
     * A copy with {@link #isIncludeVector()} changed and everything else - mode, query, filters -
     * left alone, so vectors can be toggled without disturbing an active search.
     */
    @NotNull
    public WeaviateQuerySpec withIncludeVector(boolean include) {
        if (include == this.includeVector) {
            return this;
        }
        return copy().includeVector(include).build();
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

    /**
     * The reference object for Near Object. Deliberately separate from {@link #getVector()}:
     * Near Vector is given a vector, Near Object is given an object id and the server resolves
     * that object's vector itself.
     */
    @Nullable
    public String getObjectId() {
        return objectId;
    }

    /**
     * Tenant this query is scoped to, or null. Required for a multi-tenant collection: Weaviate
     * rejects an unscoped query against one.
     */
    @Nullable
    public String getTenant() {
        return tenant;
    }

    /**
     * Autocut: keep only the first N groups of results, cutting where the score or distance
     * jumps rather than at a fixed row count.
     * <p>
     * Useful on its own for trimming a long tail of weak matches, and the natural way to bound
     * what gets fed to a generative step later -- "the results that actually matched" rather
     * than "the top 20".
     *
     * @return number of groups to keep, or null when off
     */
    @Nullable
    public Integer getAutoCut() {
        return autoCut;
    }

    /**
     * Whether the score explanation was asked for.
     * <p>
     * Off by default: it is a long string of per-term arithmetic that pushes the actual
     * properties off screen, and it costs extra work server-side to produce.
     */
    public boolean isExplainScore() {
        return explainScore;
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

    @NotNull
    public List<WeaviateFilterRow> getFilterRows() {
        return filterRows;
    }

    public boolean isAnyFilter() {
        return anyFilter;
    }

    /** Whether to fetch and show the object creation time. */
    public boolean isWithCreated() {
        return withCreated;
    }

    /** Whether to fetch and show the last update time. */
    public boolean isWithUpdated() {
        return withUpdated;
    }

    /**
     * Whether to fetch and show certainty. Only meaningful where
     * {@link WeaviateQueryMode#supportsCertainty()} holds; ignored elsewhere.
     */
    public boolean isWithCertainty() {
        return withCertainty;
    }

    /**
     * The named vectors this search targets, in the order the user listed them.
     * <p>
     * Empty means no target was given and the server chooses -- fine for a collection with a
     * single vector, ambiguous or rejected outright once it declares several. When this is
     * non-empty it supersedes {@link #getVector()}: each target carries its own query vector,
     * because vectors of different named spaces have different shapes.
     */
    @NotNull
    public List<WeaviateVectorTarget> getTargets() {
        return targets;
    }

    public boolean hasTargets() {
        return !targets.isEmpty();
    }

    /**
     * The join strategy for a multi-target search, or null.
     * <p>
     * Only meaningful with two or more targets; with one there is nothing to join. Left null
     * rather than defaulted so a single-target search sends no strategy at all.
     */
    @Nullable
    public WeaviateVectorCombination getCombination() {
        return combination;
    }

    /**
     * A builder seeded with every field of this spec.
     * <p>
     * Exists so the {@code with*} copies cannot silently drop a field. Enumerating them by hand
     * at each call site is how {@code withIncludeVector} came to lose the tenant, the object id,
     * autocut and the explain-score flag: a field added later was simply never added here.
     */
    @NotNull
    private Builder copy() {
        return builder(mode)
            .query(query)
            .vector(vector)
            .objectId(objectId)
            .tenant(tenant)
            .autoCut(autoCut)
            .explainScore(explainScore)
            .alpha(alpha)
            .queryProperties(queryProperties)
            .distance(distance)
            .fusionType(fusionType)
            .filterRows(filterRows)
            .anyFilter(anyFilter)
            .includeVector(includeVector)
            .withCreated(withCreated)
            .withUpdated(withUpdated)
            .withCertainty(withCertainty)
            .targets(targets)
            .combination(combination);
    }

    /**
     * A copy of this spec bound to {@code tenant}. Used once the user picks one, so the choice
     * sticks for later reads without rebuilding the query by hand.
     */
    @NotNull
    public WeaviateQuerySpec withTenant(@Nullable String tenant) {
        return copy().tenant(tenant).build();
    }

    public boolean rankedResults() {
        return mode != WeaviateQueryMode.FETCH;
    }

    public static final class Builder {
        private final WeaviateQueryMode mode;
        private String query;
        private float[] vector;
        private String objectId;
        private String tenant;
        private Integer autoCut;
        private boolean explainScore;
        private Float alpha;
        private List<String> queryProperties;
        private Float distance;
        private WeaviateHybridFusion fusionType;
        private List<WeaviateFilterRow> filterRows;
        private boolean anyFilter;
        private boolean includeVector;
        private boolean withCreated;
        private boolean withUpdated;
        private boolean withCertainty;
        private List<WeaviateVectorTarget> targets;
        private WeaviateVectorCombination combination;

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

        public Builder objectId(@Nullable String objectId) {
            this.objectId = objectId;
            return this;
        }

        public Builder tenant(@Nullable String tenant) {
            this.tenant = tenant;
            return this;
        }

        public Builder autoCut(@Nullable Integer autoCut) {
            this.autoCut = autoCut;
            return this;
        }

        public Builder explainScore(boolean explainScore) {
            this.explainScore = explainScore;
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

        public Builder filterRows(@Nullable List<WeaviateFilterRow> rows) {
            this.filterRows = rows;
            return this;
        }

        public Builder anyFilter(boolean any) {
            this.anyFilter = any;
            return this;
        }

        public Builder includeVector(boolean include) {
            this.includeVector = include;
            return this;
        }

        public Builder withCreated(boolean withCreated) {
            this.withCreated = withCreated;
            return this;
        }

        public Builder withUpdated(boolean withUpdated) {
            this.withUpdated = withUpdated;
            return this;
        }

        public Builder withCertainty(boolean withCertainty) {
            this.withCertainty = withCertainty;
            return this;
        }

        public Builder targets(@Nullable List<WeaviateVectorTarget> targets) {
            this.targets = targets;
            return this;
        }

        public Builder combination(@Nullable WeaviateVectorCombination combination) {
            this.combination = combination;
            return this;
        }

        @NotNull
        public WeaviateQuerySpec build() {
            return new WeaviateQuerySpec(this);
        }
    }
}
