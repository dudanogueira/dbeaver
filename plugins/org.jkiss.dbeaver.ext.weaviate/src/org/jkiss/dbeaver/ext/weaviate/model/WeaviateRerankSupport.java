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

import io.weaviate.client6.v1.api.collections.generate.GenerativeResponse;
import io.weaviate.client6.v1.api.collections.generate.GenerativeResponseGrouped;
import io.weaviate.client6.v1.api.collections.generate.GenerativeTask;
import io.weaviate.client6.v1.api.collections.generate.WeaviateGenerateClient;
import io.weaviate.client6.v1.api.collections.query.GroupBy;
import io.weaviate.client6.v1.api.collections.query.QueryOperator;
import io.weaviate.client6.v1.api.collections.query.QueryResponse;
import io.weaviate.client6.v1.api.collections.query.QueryResponseGrouped;
import io.weaviate.client6.v1.api.collections.query.WeaviateQueryClient;
import io.weaviate.client6.v1.internal.grpc.GrpcTransport;
import io.weaviate.client6.v1.internal.grpc.Rpc;
import io.weaviate.client6.v1.internal.grpc.protocol.WeaviateGrpc;
import io.weaviate.client6.v1.internal.grpc.protocol.WeaviateProtoSearchGet.SearchReply;
import io.weaviate.client6.v1.internal.grpc.protocol.WeaviateProtoSearchGet.SearchRequest;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Reads the rerank score, which the typed client throws away.
 * <p>
 * Weaviate returns a rerank score per object -- {@code MetadataResult.rerank_score} is right
 * there in the reply -- but client 6.3.1 never unmarshals it: {@code QueryMetadata} has fields
 * for distance, certainty, score and explainScore, and nothing else. A reranked search therefore
 * arrives correctly ordered with the number that produced the order missing.
 * <p>
 * Rather than reimplement the search, this observes one. The client splits a call into an
 * {@code Rpc} -- marshal the request, invoke the stub, unmarshal the reply -- so wrapping that
 * Rpc and delegating all three parts leaves behaviour identical, while the reply passes through
 * a method that can read the extra field on its way to the typed response. Request building,
 * filters, targets, metadata and object unmarshalling are all the client's own.
 * <p>
 * The seam is internal: the transport and descriptor are protected fields of a package-private
 * base class, and {@code QueryRequest.rpc} is package-private. So this reaches them by
 * reflection, resolves once, and on any failure reports itself unavailable -- the caller then
 * runs the ordinary typed call and simply shows no score column. A client upgrade that moves
 * these can cost the column; it cannot break the query.
 */
final class WeaviateRerankSupport {

    private static final Log log = Log.getLog(WeaviateRerankSupport.class);

    /** The plain search seam: QueryRequest -> QueryResponse. */
    private static final Seam QUERY = resolve(
        WeaviateQueryClient.class,
        "io.weaviate.client6.v1.api.collections.query.QueryRequest",
        false);

    /**
     * The generative seam. Same shape, different request type -- a generative search is still a
     * SearchRequest carrying the same rerank, and the reply still carries the score.
     */
    private static final Seam GENERATIVE = resolve(
        WeaviateGenerateClient.class,
        "io.weaviate.client6.v1.api.collections.generate.GenerativeRequest",
        true);

    private WeaviateRerankSupport() {
    }

    /** Whether the rerank score can be read off a plain search. */
    static boolean isAvailable() {
        return QUERY != null;
    }

    /** Whether the rerank score can be read off a generative search. */
    static boolean isGenerativeAvailable() {
        return GENERATIVE != null;
    }

    /** Whether the rerank score can be read off a grouped search. */
    static boolean isGroupedAvailable() {
        return QUERY != null && QUERY.grouped() != null;
    }

    /** Whether the rerank score can be read off a grouped generative search. */
    static boolean isGroupedGenerativeAvailable() {
        return GENERATIVE != null && GENERATIVE.grouped() != null;
    }

    /**
     * Run {@code operator} and collect the rerank score of each object into {@code scoresOut},
     * keyed by uuid.
     *
     * @return the ordinary typed response, or null when the seam is unavailable and the caller
     *         should fall back to the plain client call
     */
    @Nullable
    static QueryResponse<Map<String, Object>> search(
        @NotNull WeaviateQueryClient<Map<String, Object>> client,
        @NotNull QueryOperator operator,
        @NotNull Map<String, Float> scoresOut
    ) {
        return run(QUERY, client, scoresOut, operator, null, null);
    }

    /**
     * The grouped twin of {@link #search}. A grouped reply puts its objects under
     * {@code group_by_results}, so this exists as much to reach {@link #collectScores} with the
     * right reply shape as to send the {@code GroupBy} itself.
     */
    @Nullable
    static QueryResponseGrouped<Map<String, Object>> searchGrouped(
        @NotNull WeaviateQueryClient<Map<String, Object>> client,
        @NotNull QueryOperator operator,
        @NotNull GroupBy groupBy,
        @NotNull Map<String, Float> scoresOut
    ) {
        return run(QUERY, client, scoresOut, operator, null, groupBy);
    }

    /**
     * The generative twin of {@link #search}: same observation, on the request type the generate
     * client uses.
     */
    @Nullable
    static GenerativeResponse<Map<String, Object>> generate(
        @NotNull WeaviateGenerateClient<Map<String, Object>> client,
        @NotNull QueryOperator operator,
        @NotNull GenerativeTask task,
        @NotNull Map<String, Float> scoresOut
    ) {
        return run(GENERATIVE, client, scoresOut, operator, task, null);
    }

    /** The grouped twin of {@link #generate}. */
    @Nullable
    static GenerativeResponseGrouped<Map<String, Object>> generateGrouped(
        @NotNull WeaviateGenerateClient<Map<String, Object>> client,
        @NotNull QueryOperator operator,
        @NotNull GenerativeTask task,
        @NotNull GroupBy groupBy,
        @NotNull Map<String, Float> scoresOut
    ) {
        return run(GENERATIVE, client, scoresOut, operator, task, groupBy);
    }

    /**
     * @param task    the generative task for the generative seam, or null for the plain one; it is
     *                the only difference between the two request constructors
     * @param groupBy the grouping to apply, or null for an ungrouped search. Non-null also selects
     *                the request type's {@code grouped} factory over its {@code rpc} one, since
     *                the two produce different response types from the same reply
     */
    @Nullable
    private static <R> R run(
        @Nullable Seam seam,
        @NotNull Object client,
        @NotNull Map<String, Float> scoresOut,
        @NotNull QueryOperator operator,
        @Nullable GenerativeTask task,
        @Nullable GroupBy groupBy
    ) {
        if (seam == null) {
            return null;
        }
        Method factory = groupBy == null ? seam.rpc : seam.grouped;
        if (factory == null) {
            // Only reachable when the client has an rpc but no grouped sibling. Same contract as
            // an unresolved seam: no score column, ordinary call instead.
            return null;
        }
        GrpcTransport transport;
        Rpc<Object, SearchRequest, R, SearchReply> base;
        Object request;
        try {
            transport = (GrpcTransport) seam.transport.get(client);
            Object descriptor = seam.collection.get(client);
            Object defaults = seam.defaults.get(client);
            @SuppressWarnings("unchecked")
            Rpc<Object, SearchRequest, R, SearchReply> resolved =
                (Rpc<Object, SearchRequest, R, SearchReply>) factory.invoke(null, descriptor, defaults);
            base = resolved;
            request = task == null
                ? seam.request.newInstance(operator, groupBy)
                : seam.request.newInstance(operator, task, groupBy);
        } catch (Exception | LinkageError e) {
            // Only the reflective setup is guarded. Failing here is this class's problem, not the
            // query's, so report no score and let the caller run the ordinary call instead.
            log.debug("Rerank score unavailable; falling back to the ordinary query", e);
            return null;
        }
        // Deliberately outside the guard: once the request is on the wire, a rejection is the
        // query's own answer and must reach the caller. Swallowing it here would send every
        // failing query a second time through the fallback and report the error only then.
        return transport.performRequest(request, new ScoreReadingRpc<>(base, scoresOut));
    }

    /**
     * Delegates every part of the call and reads {@code rerank_score} off the reply in passing.
     */
    private record ScoreReadingRpc<R>(
        @NotNull Rpc<Object, SearchRequest, R, SearchReply> base,
        @NotNull Map<String, Float> scoresOut
    ) implements Rpc<Object, SearchRequest, R, SearchReply> {

        @Override
        public SearchRequest marshal(Object request) {
            return base.marshal(request);
        }

        @Override
        public R unmarshal(SearchReply reply) {
            collectScores(reply, scoresOut);
            return base.unmarshal(reply);
        }

        @Override
        public BiFunction<WeaviateGrpc.WeaviateBlockingStub, SearchRequest, SearchReply> method() {
            return base.method();
        }

        @Override
        public BiFunction<WeaviateGrpc.WeaviateFutureStub, SearchRequest,
            com.google.common.util.concurrent.ListenableFuture<SearchReply>> methodAsync() {
            return base.methodAsync();
        }
    }

    /**
     * Read every rerank score in a reply into {@code scoresOut}, keyed by uuid.
     * <p>
     * Package-private and separate from the Rpc so it can be tested against a hand-built reply,
     * which is cheaper than standing up a provider for every case. Both paths that reach it --
     * plain and generative -- share this one method, and both are verified live against Cohere,
     * which reranks and generates.
     */
    static void collectScores(@NotNull SearchReply reply, @NotNull Map<String, Float> scoresOut) {
        for (var result : reply.getResultsList()) {
            collectScore(result.getMetadata(), scoresOut);
        }
        // A grouped reply carries its objects under group_by_results instead, and leaves results
        // empty. Reading only the list above is why a grouped rerank came back with the ordering
        // applied and every score blank.
        for (var group : reply.getGroupByResultsList()) {
            for (var result : group.getObjectsList()) {
                collectScore(result.getMetadata(), scoresOut);
            }
        }
    }

    private static void collectScore(
        @Nullable io.weaviate.client6.v1.internal.grpc.protocol.WeaviateProtoSearchGet.MetadataResult metadata,
        @NotNull Map<String, Float> scoresOut
    ) {
        // rerankScorePresent, not a zero check: 0.0 is a real score.
        if (metadata != null && metadata.getRerankScorePresent()) {
            scoresOut.put(metadata.getId(), (float) metadata.getRerankScore());
        }
    }

    /**
     * @param grouped the request type's {@code grouped} factory, or null on a client that has no
     *                such sibling -- in which case ungrouped rerank still works
     */
    private record Seam(
        Field transport, Field collection, Field defaults,
        Method rpc, @Nullable Method grouped, Constructor<?> request
    ) {
    }

    @Nullable
    private static Seam resolve(
        @NotNull Class<?> clientClass,
        @NotNull String requestClassName,
        boolean generative
    ) {
        try {
            Field transport = declaredField(clientClass, "grpcTransport");
            Field collection = declaredField(clientClass, "collection");
            Field defaults = declaredField(clientClass, "defaults");

            Class<?> requestClass = Class.forName(requestClassName);
            Method rpc = requestClass.getDeclaredMethod("rpc",
                Class.forName("io.weaviate.client6.v1.internal.orm.CollectionDescriptor"),
                Class.forName("io.weaviate.client6.v1.api.collections.CollectionHandleDefaults"));
            rpc.setAccessible(true);
            Constructor<?> request = generative
                ? requestClass.getDeclaredConstructor(QueryOperator.class, GenerativeTask.class, GroupBy.class)
                : requestClass.getDeclaredConstructor(QueryOperator.class, GroupBy.class);
            request.setAccessible(true);
            // Resolved separately and allowed to be missing: losing the grouped factory must not
            // take the ungrouped score column down with it.
            Method grouped = declaredMethodOrNull(requestClass, "grouped");
            return new Seam(transport, collection, defaults, rpc, grouped, request);
        } catch (Exception | LinkageError e) {
            log.warn("Weaviate client internals moved (" + requestClassName + "): rerank scores "
                + "will not be shown for that path. The reranked ordering itself is unaffected.", e);
            return null;
        }
    }

    @Nullable
    private static Method declaredMethodOrNull(@NotNull Class<?> type, @NotNull String name) {
        try {
            Method method = type.getDeclaredMethod(name,
                Class.forName("io.weaviate.client6.v1.internal.orm.CollectionDescriptor"),
                Class.forName("io.weaviate.client6.v1.api.collections.CollectionHandleDefaults"));
            method.setAccessible(true);
            return method;
        } catch (Exception | LinkageError e) {
            log.warn("Weaviate client has no " + type.getSimpleName() + "." + name + ": rerank "
                + "scores will not be shown on grouped searches.", e);
            return null;
        }
    }

    @NotNull
    private static Field declaredField(@NotNull Class<?> type, @NotNull String name)
        throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Declared on a superclass; keep walking.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
