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

import io.weaviate.client6.v1.api.collections.query.QueryOperator;
import io.weaviate.client6.v1.api.collections.query.QueryResponse;
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

    private static final Handles HANDLES = resolve();

    private WeaviateRerankSupport() {
    }

    /** Whether the rerank score can be read at all, i.e. whether the seam resolved. */
    static boolean isAvailable() {
        return HANDLES != null;
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
        Handles handles = HANDLES;
        if (handles == null) {
            return null;
        }
        try {
            GrpcTransport transport = (GrpcTransport) handles.transport.get(client);
            Object descriptor = handles.collection.get(client);
            Object defaults = handles.defaults.get(client);
            @SuppressWarnings("unchecked")
            Rpc<Object, SearchRequest, QueryResponse<Map<String, Object>>, SearchReply> base =
                (Rpc<Object, SearchRequest, QueryResponse<Map<String, Object>>, SearchReply>)
                    handles.rpc.invoke(null, descriptor, defaults);
            Object request = handles.request.newInstance(operator, null);
            return transport.performRequest(request, new ScoreReadingRpc(base, scoresOut));
        } catch (Exception e) {
            // A failure here is this class's problem, not the query's: report no score and let
            // the caller run the plain call. Anything the server rejected surfaces there too.
            log.debug("Rerank score unavailable; falling back to the plain query", e);
            return null;
        }
    }

    /**
     * Delegates every part of the call and reads {@code rerank_score} off the reply in passing.
     */
    private record ScoreReadingRpc(
        @NotNull Rpc<Object, SearchRequest, QueryResponse<Map<String, Object>>, SearchReply> base,
        @NotNull Map<String, Float> scoresOut
    ) implements Rpc<Object, SearchRequest, QueryResponse<Map<String, Object>>, SearchReply> {

        @Override
        public SearchRequest marshal(Object request) {
            return base.marshal(request);
        }

        @Override
        public QueryResponse<Map<String, Object>> unmarshal(SearchReply reply) {
            for (var result : reply.getResultsList()) {
                var metadata = result.getMetadata();
                // rerankScorePresent, not a zero check: 0.0 is a real score.
                if (metadata != null && metadata.getRerankScorePresent()) {
                    scoresOut.put(metadata.getId(), (float) metadata.getRerankScore());
                }
            }
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

    private record Handles(
        Field transport, Field collection, Field defaults, Method rpc, Constructor<?> request
    ) {
    }

    @Nullable
    private static Handles resolve() {
        try {
            Class<?> queryClient = WeaviateQueryClient.class;
            Field transport = declaredField(queryClient, "grpcTransport");
            Field collection = declaredField(queryClient, "collection");
            Field defaults = declaredField(queryClient, "defaults");

            Class<?> requestClass =
                Class.forName("io.weaviate.client6.v1.api.collections.query.QueryRequest");
            Method rpc = requestClass.getDeclaredMethod("rpc",
                Class.forName("io.weaviate.client6.v1.internal.orm.CollectionDescriptor"),
                Class.forName("io.weaviate.client6.v1.api.collections.CollectionHandleDefaults"));
            rpc.setAccessible(true);
            Constructor<?> request = requestClass.getDeclaredConstructor(
                QueryOperator.class,
                Class.forName("io.weaviate.client6.v1.api.collections.query.GroupBy"));
            request.setAccessible(true);
            return new Handles(transport, collection, defaults, rpc, request);
        } catch (Exception | LinkageError e) {
            log.warn("Weaviate client internals moved: rerank scores will not be shown. "
                + "The reranked ordering itself is unaffected.", e);
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
