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

import io.weaviate.client6.v1.api.collections.generate.GenerativeProvider;
import io.weaviate.client6.v1.api.collections.generate.GenerativeTask;
import io.weaviate.client6.v1.api.collections.query.Bm25;
import io.weaviate.client6.v1.api.collections.query.FetchObjects;
import io.weaviate.client6.v1.api.collections.query.Filter;
import io.weaviate.client6.v1.api.collections.query.GroupBy;
import io.weaviate.client6.v1.api.collections.query.Hybrid;
import io.weaviate.client6.v1.api.collections.query.Metadata;
import io.weaviate.client6.v1.api.collections.query.NearObject;
import io.weaviate.client6.v1.api.collections.query.NearText;
import io.weaviate.client6.v1.api.collections.query.NearVector;
import io.weaviate.client6.v1.api.collections.query.NearVectorTarget;
import io.weaviate.client6.v1.api.collections.query.Rerank;
import io.weaviate.client6.v1.api.collections.query.SortBy;
import io.weaviate.client6.v1.api.collections.query.Target;
import io.weaviate.client6.v1.internal.ObjectBuilder;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.data.DBDAttributeConstraint;
import org.jkiss.dbeaver.model.data.DBDDataFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns a {@link WeaviateQuerySpec} into the request objects the client wants.
 * <p>
 * Everything here is a pure function of the spec: no connection, no session, no collection state.
 * That is the whole reason it is a separate class. {@link WeaviateCollection} owns the dispatch --
 * which operator to call and on which handle -- while this owns the translation, and the two grow
 * for different reasons. A new search option is a change here alone; a new way to run a search is
 * a change there alone.
 * <p>
 * Being separate also makes it reachable. These methods were {@code private static} on a
 * 2,400-line class, so nothing could test the one part of the query path with no I/O in it at all.
 */
final class WeaviateQueryRequest {

    private WeaviateQueryRequest() {
        // Utility class.
    }

    @NotNull
    static GroupBy buildGroupBy(@NotNull WeaviateGroupBySpec spec) {
        return GroupBy.property(
            spec.getProperty(), spec.getMaxGroups(), spec.getMaxObjectsPerGroup());
    }

    // One options method per mode, shared verbatim by the plain and generative dispatchers.
    // Concrete builder types throughout: the ancestors carrying the shared setters are
    // package-private in the client, so there is no common type to abstract over.

    static Bm25.Builder bm25Options(
        @NotNull Bm25.Builder b, @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter, int limit, int offset
    ) {
        applyCommon(b, spec, filter, limit, offset);
        b.returnMetadata(scoreMetadata(spec));
        List<String> queryProperties = spec.getQueryProperties();
        if (!queryProperties.isEmpty()) b.queryProperties(queryProperties);
        return b;
    }

    static NearText.Builder nearTextOptions(
        @NotNull NearText.Builder b, @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter, int limit, int offset
    ) {
        applyCommon(b, spec, filter, limit, offset);
        Rerank rerank = buildRerank(spec);
        if (rerank != null) b.rerank(rerank);
        b.returnMetadata(Metadata.DISTANCE);
        if (spec.getDistance() != null) b.distance(spec.getDistance());
        return b;
    }

    static NearVector.Builder nearVectorOptions(
        @NotNull NearVector.Builder b, @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter, int limit, int offset
    ) {
        applyCommon(b, spec, filter, limit, offset);
        Rerank rerank = buildRerank(spec);
        if (rerank != null) b.rerank(rerank);
        b.returnMetadata(Metadata.DISTANCE);
        if (spec.getDistance() != null) b.distance(spec.getDistance());
        return b;
    }

    static NearObject.Builder nearObjectOptions(
        @NotNull NearObject.Builder b, @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter, int limit, int offset
    ) {
        applyCommon(b, spec, filter, limit, offset);
        Rerank rerank = buildRerank(spec);
        if (rerank != null) b.rerank(rerank);
        b.returnMetadata(Metadata.DISTANCE);
        if (spec.getDistance() != null) b.distance(spec.getDistance());
        return b;
    }

    static Hybrid.Builder hybridOptions(
        @NotNull Hybrid.Builder b, @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter, int limit, int offset
    ) {
        applyCommon(b, spec, filter, limit, offset);
        b.returnMetadata(scoreMetadata(spec));
        if (spec.getAlpha() != null) b.alpha(spec.getAlpha());
        if (spec.getFusionType() != null) b.fusionType(spec.getFusionType().toClientType());
        return b;
    }

    static FetchObjects.Builder fetchOptions(
        @NotNull FetchObjects.Builder b, @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter, @NotNull List<SortBy> sortBy, int limit, int offset
    ) {
        applyCommon(b, spec, filter, limit, offset);
        if (!sortBy.isEmpty()) b.sort(sortBy);
        return b;
    }

    @NotNull
    static float[] requireVector(@NotNull WeaviateQuerySpec spec) {
        float[] vector = spec.getVector();
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("Near Vector mode requires a non-empty vector");
        }
        return vector;
    }

    /**
     * Near Vector is handed a vector; Near Object is handed an object id and the server
     * resolves that object's stored vector itself, so this works even when the reference
     * object's vector is never returned to the client.
     */
    @NotNull
    static String requireObjectId(@NotNull WeaviateQuerySpec spec) {
        String uuid = spec.getObjectId();
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalStateException(
                "Near Object mode requires the UUID of a reference object");
        }
        return uuid;
    }

    /**
     * The spec's generative task as the client wants it. The provider override only rides along
     * when one was chosen -- with none sent, the server falls back to the collection's own
     * generative module, which is the ordinary case.
     */
    @NotNull
    static ObjectBuilder<GenerativeTask> configureTask(
        @NotNull GenerativeTask.Builder t,
        @NotNull WeaviateGenerativeTask spec
    ) {
        GenerativeProvider provider = spec.getProvider() == null ? null
            : spec.getProvider().toClientProvider(
                spec.getModel(), spec.getTemperature(), spec.getMaxTokens());
        if (spec.getSinglePrompt() != null) {
            t.singlePrompt(spec.getSinglePrompt(), sb -> {
                if (spec.isReturnMetadata()) sb.metadata(true);
                if (provider != null) sb.generativeProvider(provider);
                return sb;
            });
        }
        if (spec.getGroupedTask() != null) {
            t.groupedTask(spec.getGroupedTask(), gb -> {
                if (!spec.getGroupedProperties().isEmpty()) {
                    gb.properties(spec.getGroupedProperties());
                }
                if (spec.isReturnMetadata()) gb.metadata(true);
                if (provider != null) gb.generativeProvider(provider);
                return gb;
            });
        }
        return t;
    }

    /**
     * The Target for a text-driven search -- Near Text or Hybrid -- carrying both the query text
     * and the named vectors to embed it against.
     * <p>
     * A single target sends no join strategy: there is nothing to join, and the client's combined
     * record would insist on one anyway.
     */
    @NotNull
    static Target buildTextTarget(@NotNull WeaviateQuerySpec spec, @NotNull String text) {
        List<WeaviateVectorTarget> targets = spec.getTargets();
        List<String> queries = List.of(text);
        if (targets.size() == 1) {
            return new Target.TextTarget(weightOf(targets.get(0)), queries);
        }
        List<Target.VectorWeight> weights = new ArrayList<>(targets.size());
        for (WeaviateVectorTarget target : targets) {
            weights.add(weightOf(target));
        }
        return new Target.CombinedTextTarget(queries, combinationOf(spec), weights);
    }

    /**
     * The Target for Near Vector, where each named vector is searched with its own query vector.
     * Different vector spaces have different shapes, so there is no one vector to share.
     */
    @NotNull
    static NearVectorTarget buildVectorTarget(@NotNull WeaviateQuerySpec spec) {
        List<WeaviateVectorTarget> targets = spec.getTargets();
        List<Target.VectorTarget> vectorTargets = new ArrayList<>(targets.size());
        for (WeaviateVectorTarget target : targets) {
            Object vector = target.queryVectorForClient();
            if (vector == null) {
                throw new IllegalStateException(
                    "Near Vector target " + target.getName() + " has no query vector");
            }
            vectorTargets.add(new Target.VectorTarget(target.getName(), target.getWeight(), vector));
        }
        if (vectorTargets.size() == 1) {
            return vectorTargets.get(0);
        }
        return new Target.CombinedVectorTarget(combinationOf(spec), vectorTargets);
    }

    @NotNull
    static Target.VectorWeight weightOf(@NotNull WeaviateVectorTarget target) {
        return new Target.VectorWeight(target.getName(), target.getWeight());
    }

    /**
     * The spec's rerank request as the client type, or null for none. Attached inline in the
     * near_* dispatch arms -- only their builders expose rerank in client 6.3.1 (see
     * {@link WeaviateQueryMode#supportsRerank()}), and their common ancestor carrying the
     * setter is package-private, so there is no type to write a shared helper against.
     */
    @Nullable
    static Rerank buildRerank(@NotNull WeaviateQuerySpec spec) {
        WeaviateRerankSpec rerank = spec.getRerank();
        if (rerank == null) {
            return null;
        }
        String query = rerank.getQuery();
        return query == null
            ? Rerank.by(rerank.getProperty())
            : Rerank.by(rerank.getProperty(), rb -> rb.query(query));
    }

    /**
     * The join strategy to send for a multi-target search. Defaults to MIN, which is what Weaviate
     * itself falls back to -- the client's combined records require a strategy, so there is no way
     * to send "unspecified" and let the server decide.
     */
    @NotNull
    static Target.CombinationMethod combinationOf(@NotNull WeaviateQuerySpec spec) {
        WeaviateVectorCombination combination = spec.getCombination();
        return combination == null
            ? WeaviateVectorCombination.MIN.toClientType()
            : combination.toClientType();
    }

    static <B extends io.weaviate.client6.v1.api.collections.query.BaseQueryOptions.Builder<B, ?>>
    void applyCommon(
        @NotNull B b,
        @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter,
        int limit,
        int offset
    ) {
        if (limit > 0) b.limit(limit);
        if (offset > 0) b.offset(offset);
        if (filter != null) b.filters(filter);
        if (spec.isIncludeVector()) {
            // Ask for only the targeted vectors when the search names any, so the grid columns
            // built from the same list in readData are the ones that actually come back.
            List<String> requested = targetVectorNames(spec);
            if (requested.isEmpty()) {
                b.includeVector();
            } else {
                b.includeVector(requested);
            }
        }
        // The client calls Weaviate's autocut "autolimit"; the wire field is autocut.
        Integer autoCut = spec.getAutoCut();
        if (autoCut != null && autoCut > 0 && spec.getMode().supportsAutoCut()) {
            b.autolimit(autoCut);
        }
        // Opt-in metadata. returnMetadata is additive across calls on the same builder, so this
        // does not disturb the per-mode SCORE/DISTANCE requests made at the dispatch sites.
        List<Metadata> extra = extraMetadata(spec);
        if (!extra.isEmpty()) {
            b.returnMetadata(extra);
        }
    }

    /**
     * The metadata the user opted into beyond what the mode itself needs, ready to request.
     * Certainty is gated on the mode: the server derives it from vector distance, so asking for
     * it elsewhere returns nothing and the flag is simply ignored.
     */
    @NotNull
    static List<Metadata> extraMetadata(@NotNull WeaviateQuerySpec spec) {
        List<Metadata> extra = new ArrayList<>(3);
        if (spec.isWithCreated()) extra.add(Metadata.CREATION_TIME_UNIX);
        if (spec.isWithUpdated()) extra.add(Metadata.LAST_UPDATE_TIME_UNIX);
        if (spec.isWithCertainty() && spec.getMode().supportsCertainty()) {
            extra.add(Metadata.CERTAINTY);
        }
        return extra;
    }

    /**
     * Whether a rerank score column is offered for this spec.
     * <p>
     * Everything here is knowable before the query runs, which is what lets the column be
     * offered only when it will actually be filled: the search must be reranked, the seam that
     * reads the score must have resolved, and the read must not be generative -- the generate
     * client uses its own Rpc, which this does not wrap.
     */
    static boolean hasRerankScore(@NotNull WeaviateQuerySpec spec) {
        if (spec.getRerank() == null) {
            return false;
        }
        // Each path has its own seam, and any can resolve without the others. Grouped is a
        // separate seam again: a grouped reply carries its objects somewhere else entirely, so
        // reading a score off one is not the same operation as reading it off a flat reply.
        if (spec.isGrouped()) {
            return spec.getGenerative() != null
                ? WeaviateRerankSupport.isGroupedGenerativeAvailable()
                : WeaviateRerankSupport.isGroupedAvailable();
        }
        return spec.getGenerative() != null
            ? WeaviateRerankSupport.isGenerativeAvailable()
            : WeaviateRerankSupport.isAvailable();
    }

    /**
     * The declared vectors a query is about: its targets when it names any, all of them otherwise.
     * A target the collection no longer declares is dropped rather than turned into a blank column.
     */
    @NotNull
    static List<String> narrowToTargets(
        @NotNull WeaviateQuerySpec spec,
        @NotNull List<String> declared
    ) {
        List<String> targets = targetVectorNames(spec);
        if (targets.isEmpty()) {
            return declared;
        }
        List<String> out = new ArrayList<>(targets.size());
        for (String name : targets) {
            if (declared.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * Distinct target names of a spec, in order, or empty when it names none.
     * <p>
     * The one rule for which vectors a query is about: readData builds the grid's vector columns
     * from it and applyCommon asks the server for exactly those. Deriving the two separately is
     * how columns come to be permanently blank, or data to arrive with nowhere to go.
     */
    @NotNull
    static List<String> targetVectorNames(@NotNull WeaviateQuerySpec spec) {
        List<String> names = new ArrayList<>(spec.getTargets().size());
        for (WeaviateVectorTarget target : spec.getTargets()) {
            if (!names.contains(target.getName())) {
                names.add(target.getName());
            }
        }
        return names;
    }

    /**
     * Metadata to request for a scored query. The explanation is only asked for when it will be
     * shown -- producing it is extra server-side work for a column nobody looked at.
     */
    @NotNull
    static Metadata[] scoreMetadata(@NotNull WeaviateQuerySpec spec) {
        return spec.isExplainScore()
            ? new Metadata[]{Metadata.SCORE, Metadata.EXPLAIN_SCORE}
            : new Metadata[]{Metadata.SCORE};
    }

    @NotNull
    static String requireQuery(@NotNull WeaviateQuerySpec spec, @NotNull String modeLabel) {
        String text = spec.getQuery();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(modeLabel + " mode requires a query string");
        }
        return text;
    }

    @NotNull
    static List<SortBy> buildSortBy(
        @Nullable DBDDataFilter dataFilter,
        @NotNull List<WeaviateProperty> attributes
    ) {
        if (dataFilter == null) {
            return Collections.emptyList();
        }
        List<DBDAttributeConstraint> ordered = new ArrayList<>();
        for (DBDAttributeConstraint c : dataFilter.getConstraints()) {
            if (c.getOrderPosition() > 0) {
                ordered.add(c);
            }
        }
        if (ordered.isEmpty()) {
            return Collections.emptyList();
        }
        ordered.sort((a, b) -> Integer.compare(a.getOrderPosition(), b.getOrderPosition()));
        List<SortBy> out = new ArrayList<>(ordered.size());
        for (DBDAttributeConstraint c : ordered) {
            String name = c.getAttribute() != null ? c.getAttribute().getName() : c.getAttributeName();
            if (name == null || name.isEmpty()) continue;
            SortBy sort = mapSortBy(name);
            out.add(c.isOrderDescending() ? sort.desc() : sort.asc());
        }
        return out;
    }

    @NotNull
    static SortBy mapSortBy(@NotNull String name) {
        if (WeaviateColumns.UUID.equalsIgnoreCase(name)) {
            return SortBy.uuid();
        }
        return SortBy.property(name);
    }
}
