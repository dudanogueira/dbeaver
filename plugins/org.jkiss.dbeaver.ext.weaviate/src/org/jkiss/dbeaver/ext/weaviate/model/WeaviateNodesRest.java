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

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * Reads {@code GET /v1/nodes?output=verbose} over REST, keeping every value the server sent.
 * <p>
 * Not through {@code client.cluster.listNodes}, and the reason is a silent one. The client models
 * a shard's status as the enum {@code VectorIndexingStatus}, which has three constants -- READONLY,
 * INDEXING, READY. The server has six ({@code entities/storagestate/status.go}): those three plus
 * LOADING, LAZY_LOADING and SHUTDOWN. Gson's enum adapter maps a name it does not recognise to
 * <em>null</em>, so a lazy-loading shard arrives with no status at all. Nothing throws and nothing
 * is logged; the shard simply loses its state, and with it its label, its colour, and its place in
 * any action that filters on status. On a server with 4000 lazily-loaded tenant shards, that is
 * every shard.
 * <p>
 * The same record misspells the queue field as {@code vectorQueueLenght}, so the server's
 * {@code vectorQueueLength} never binds and the value is always 0.
 * <p>
 * Keeping the status as a {@link String} fixes both, and leaves the plugin able to display a
 * status a future server invents without a client release. {@link WeaviateShardStatus} is what
 * gives a known name its meaning; an unknown one still reaches the user as text.
 */
public final class WeaviateNodesRest {

    private static final String NODES_PATH = "/v1/nodes?output=verbose";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private WeaviateNodesRest() {
    }

    /** One cluster node, with only the fields the navigator shows. */
    public record NodeInfo(
        @SerializedName("name") String name,
        @SerializedName("status") String status,
        @SerializedName("version") String version,
        @SerializedName("gitHash") String gitHash,
        @SerializedName("stats") NodeStats stats,
        @SerializedName("shards") List<ShardInfo> shards
    ) {
        @NotNull
        public List<ShardInfo> shardsOrEmpty() {
            return shards == null ? Collections.emptyList() : shards;
        }
    }

    public record NodeStats(
        @SerializedName("objectCount") long objectCount,
        @SerializedName("shardCount") int shardCount
    ) {
    }

    /**
     * One shard on one node.
     * <p>
     * {@code vectorIndexingStatus} is a String on purpose -- see the class comment. It is also the
     * only status a shard has: the name reads as though it described the vector index alone, but
     * it reports READONLY once a shard has been set read-only, which was verified by setting one
     * and watching this field change. So there is no second status to fetch.
     */
    public record ShardInfo(
        @SerializedName("name") String name,
        @SerializedName("class") String collection,
        @SerializedName("objectCount") long objectCount,
        @SerializedName("vectorIndexingStatus") String vectorIndexingStatus,
        @SerializedName("vectorQueueLength") int vectorQueueLength,
        @SerializedName("compressed") boolean compressed,
        @SerializedName("loaded") boolean loaded,
        @SerializedName("numberOfReplicas") int numberOfReplicas,
        @SerializedName("replicationFactor") int replicationFactor
    ) {
    }

    private record NodesResponse(@SerializedName("nodes") List<NodeInfo> nodes) {
    }

    /**
     * Every node the cluster reports, with its shards.
     * <p>
     * Verbose output is a large response -- a megabyte on a server with a few thousand tenants --
     * which is why callers cache it and why the tree only asks for it once Cluster Nodes is
     * actually opened.
     */
    @NotNull
    public static List<NodeInfo> listNodes(@NotNull WeaviateDataSource dataSource) throws DBException {
        URI uri = URI.create(dataSource.getRestBaseUrl() + NODES_PATH);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .GET();

        WeaviateRestHeaders.applyTo(request, dataSource);

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DBException("Cannot reach Weaviate at " + uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DBException("Interrupted while listing cluster nodes", e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new DBException("Cannot list cluster nodes: HTTP " + status + " " + response.body());
        }
        NodesResponse parsed;
        try {
            parsed = new Gson().fromJson(response.body(), NodesResponse.class);
        } catch (JsonSyntaxException e) {
            throw new DBException("Cannot read the cluster node list: " + e.getMessage(), e);
        }
        return parsed == null || parsed.nodes() == null ? Collections.emptyList() : parsed.nodes();
    }

    /** The status a shard reports, or null when the server sent none. */
    @Nullable
    public static String statusOf(@NotNull ShardInfo shard) {
        String status = shard.vectorIndexingStatus();
        return status == null || status.isBlank() ? null : status;
    }
}
