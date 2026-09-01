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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The seam between what a replicating cluster sends and what this plugin reads.
 * <p>
 * Needs a multi-node cluster started with {@code REPLICA_MOVEMENT_ENABLED=true} and the fixture
 * from {@code testdata/seed_replication_fixture.py}. Read-only: a movement is real work on real
 * shards, and a test that started one would leave it running when it failed.
 */
public class WeaviateReplicationLiveTest extends DBeaverUnitTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String FIXTURE = "DBeaverReplicaFixture";

    private static String baseUrl() {
        String url = System.getenv("WEAVIATE_REPLICATION_FIXTURE_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
            "set WEAVIATE_REPLICATION_FIXTURE_URL to run the live replication tests");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Called first in every test rather than from a helper, so an abort is not swallowed. */
    private static HttpResponse<String> get(String path) {
        String base = baseUrl();
        String key = System.getenv().getOrDefault("WEAVIATE_API_KEY", "root-user-key");
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path))
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + key)
            .GET()
            .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("GET " + path + " failed: " + e.getMessage(), e);
        }
    }

    private static String body(String path) {
        HttpResponse<String> response = get(path);
        Assumptions.assumeTrue(response.statusCode() != 501,
            "replica movement is disabled on this server; start it with "
                + "REPLICA_MOVEMENT_ENABLED=true");
        Assertions.assertEquals(200, response.statusCode(),
            () -> "GET " + path + " -> " + response.statusCode() + " " + response.body());
        return response.body();
    }

    @Test
    public void theFeatureIsReachableAndDistinguishableFromEmpty() {
        // The point worth pinning: unlike RBAC and backups, "switched off" is a distinct answer
        // here. 501 means disabled; 200 with [] means nothing is in flight.
        HttpResponse<String> response = get("/v1/replication/replicate/list");
        Assertions.assertTrue(response.statusCode() == 200 || response.statusCode() == 501,
            () -> "unexpected status " + response.statusCode() + ": " + response.body());
    }

    @Test
    public void shardingStateNamesRealNodes() {
        JsonObject root = JsonParser.parseString(
            body("/v1/replication/sharding-state?collection=" + FIXTURE)).getAsJsonObject();
        JsonObject state = root.getAsJsonObject("shardingState");
        Assertions.assertNotNull(state, "no shardingState in the response");
        JsonArray shards = state.getAsJsonArray("shards");
        Assumptions.assumeTrue(shards != null && !shards.isEmpty(),
            "no shards in " + FIXTURE + " -- run testdata/seed_replication_fixture.py");
        for (JsonElement e : shards) {
            JsonObject shard = e.getAsJsonObject();
            // "shard", not "shardName". The bundled client's record uses the latter with no
            // @SerializedName, which is why that field is null on every server version.
            Assertions.assertTrue(shard.has("shard"),
                () -> "a shard entry with no 'shard' field: " + shard);
            Assertions.assertFalse(shard.get("shard").getAsString().isBlank());
            Assertions.assertTrue(shard.has("replicas") && shard.get("replicas").isJsonArray(),
                () -> "no replicas for " + shard);
        }
    }

    @Test
    public void shardingStateRequiresACollection() {
        // The OpenAPI document marks the parameter optional; the server does not agree. Worth a
        // test so the plugin is not "fixed" to omit it.
        HttpResponse<String> response = get("/v1/replication/sharding-state");
        Assumptions.assumeTrue(response.statusCode() != 501, "replica movement is disabled");
        Assertions.assertEquals(400, response.statusCode(),
            () -> "expected 400 without a collection, got " + response.statusCode());
    }

    @Test
    public void everyStateTheServerReportsIsOneThisPluginKnows() {
        // The seam. A state added in a later release shows up here as a failure naming it, rather
        // than as a blank row in somebody's tree.
        List<String> unknown = new ArrayList<>();
        for (JsonElement e : JsonParser.parseString(
            body("/v1/replication/replicate/list?includeHistory=true")).getAsJsonArray()) {
            WeaviateReplicationRest.OperationInfo op =
                WeaviateReplicationRest.parseOperation(e.getAsJsonObject());
            if (op == null) {
                continue;
            }
            if (!op.state().isEmpty() && !WeaviateReplicationState.isKnown(op.state())) {
                unknown.add(op.state());
            }
            for (WeaviateReplicationRest.StatusInfo step : op.statusHistory()) {
                if (!step.state().isEmpty() && !WeaviateReplicationState.isKnown(step.state())) {
                    unknown.add(step.state());
                }
            }
        }
        Assertions.assertTrue(unknown.isEmpty(),
            () -> "states this plugin cannot name: " + unknown);
    }

    @Test
    public void operationsParseWithTheFieldsTheUiDependsOn() {
        JsonArray ops = JsonParser.parseString(
            body("/v1/replication/replicate/list?includeHistory=true")).getAsJsonArray();
        Assumptions.assumeFalse(ops.isEmpty(),
            "no replication operations on this server; start one to exercise this");
        for (JsonElement e : ops) {
            WeaviateReplicationRest.OperationInfo op =
                WeaviateReplicationRest.parseOperation(e.getAsJsonObject());
            Assertions.assertNotNull(op, () -> "did not parse: " + e);
            Assertions.assertFalse(op.id().isBlank(), "an operation with no id");
            Assertions.assertFalse(op.collection().isBlank(), () -> op.id() + " has no collection");
            Assertions.assertFalse(op.shard().isBlank(), () -> op.id() + " has no shard");
            Assertions.assertFalse(op.sourceNode().isBlank(), () -> op.id() + " has no source");
            Assertions.assertFalse(op.targetNode().isBlank(), () -> op.id() + " has no target");
            Assertions.assertNotEquals(WeaviateReplicationType.UNKNOWN,
                WeaviateReplicationType.fromName(op.type()),
                () -> op.id() + " has an unrecognised type " + op.type());
        }
    }

    @Test
    public void theClusterHasSomewhereToMoveTo() {
        // Replica movement needs a target that does not already hold the shard, so a single-node
        // cluster cannot exercise any of this.
        JsonObject nodes = JsonParser.parseString(body("/v1/nodes")).getAsJsonObject();
        int count = nodes.getAsJsonArray("nodes").size();
        Assertions.assertTrue(count >= 2,
            () -> "replica movement needs at least two nodes, this cluster has " + count);
    }
}
