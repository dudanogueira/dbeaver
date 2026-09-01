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
import com.google.gson.JsonSyntaxException;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Weaviate's replica-movement API, over REST.
 * <p>
 * Not through the bundled client, and the case is stronger here than it was for cluster nodes or
 * RBAC: those clients are wrong about servers newer than themselves, whereas this one is wrong
 * about the server you have today. Three separate defects, verified against 6.3.1:
 * <ul>
 *   <li>{@code ReplicationStatus.errors} is declared {@code List<String>} and the server sends
 *       {@code {message, whenErroredUnixMs}} objects, so Gson <em>throws</em>
 *       {@code Expected a string but was BEGIN_OBJECT}. Reading any operation that recorded an
 *       error fails -- which is exactly the operation somebody opened the screen to look at;</li>
 *   <li>{@code ReplicationState} has no {@code INTEGRATING}. The server has emitted it since 1.38
 *       and a copy passes through it on the way to READY, so a live move shows a null state
 *       during a phase it genuinely occupies;</li>
 *   <li>{@code ShardReplica.shardName} carries no {@code @SerializedName} and the server sends
 *       {@code shard}, so the field is null on every server version. A sharding view built on it
 *       renders a column of blanks.</li>
 * </ul>
 * The client also drops {@code uncancelable}, {@code scheduledForCancel} and
 * {@code scheduledForDelete} -- the three flags that say whether Cancel and Delete would be
 * accepted -- and has no binding at all for {@code force-delete}. Going through it would mean
 * offering buttons that 409 and omitting the one escape hatch for a stuck operation.
 * <p>
 * So: states and types are {@link String}, errors are a record of what the server actually sends,
 * and a field this build has never heard of is ignored rather than fatal.
 */
public final class WeaviateReplicationRest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final String BASE_PATH = "/v1/replication";

    private WeaviateReplicationRest() {
    }

    /**
     * Thrown when the server answers 501, which it does for every replication endpoint except
     * force-delete when {@code REPLICA_MOVEMENT_ENABLED} is false.
     * <p>
     * Worth its own type. Unlike RBAC and backups -- where a disabled feature and an empty one are
     * indistinguishable and the plugin has to hedge -- here the server says plainly which it is,
     * and the tree can say so too.
     */
    public static class DisabledException extends DBException {
        public DisabledException(@NotNull String message) {
            super(message);
        }
    }

    // -- Value types ------------------------------------------------------------------------

    /** One error the server recorded against a state. */
    public record ErrorInfo(long whenErroredUnixMs, @NotNull String message) {
    }

    /**
     * One state an operation has been in, with anything that went wrong while it was there.
     *
     * @param whenStartedUnixMs 0 when the server omitted it, which it does for the first entry
     */
    public record StatusInfo(
        @NotNull String state,
        @NotNull List<ErrorInfo> errors,
        long whenStartedUnixMs
    ) {
        public StatusInfo {
            errors = errors == null ? List.of() : List.copyOf(errors);
        }

        public boolean hasErrors() {
            return !errors.isEmpty();
        }
    }

    /**
     * One replica-movement operation.
     *
     * @param uncancelable       set once the replica has joined the sharding state; from then on
     *                           the server refuses to cancel, and refuses to delete unless READY
     * @param scheduledForCancel a cancel has been accepted and is being carried out
     * @param scheduledForDelete a delete has been accepted and is being carried out
     */
    public record OperationInfo(
        @NotNull String id,
        @NotNull String collection,
        @NotNull String shard,
        @NotNull String sourceNode,
        @NotNull String targetNode,
        @NotNull String type,
        @Nullable StatusInfo status,
        @NotNull List<StatusInfo> statusHistory,
        boolean uncancelable,
        boolean scheduledForCancel,
        boolean scheduledForDelete,
        long whenStartedUnixMs
    ) {
        public OperationInfo {
            statusHistory = statusHistory == null ? List.of() : List.copyOf(statusHistory);
        }

        @NotNull
        public String state() {
            return status == null ? "" : status.state();
        }

        /** Every error across the current state and the history, oldest first. */
        @NotNull
        public List<ErrorInfo> allErrors() {
            List<ErrorInfo> all = new ArrayList<>();
            for (StatusInfo step : statusHistory) {
                all.addAll(step.errors());
            }
            if (status != null) {
                all.addAll(status.errors());
            }
            return all;
        }
    }

    /** One shard and the nodes holding a replica of it. */
    public record ShardReplicas(@NotNull String shard, @NotNull List<String> replicas) {
        public ShardReplicas {
            replicas = replicas == null ? List.of() : List.copyOf(replicas);
        }
    }

    public record ShardingState(@NotNull String collection, @NotNull List<ShardReplicas> shards) {
        public ShardingState {
            shards = shards == null ? List.of() : List.copyOf(shards);
        }
    }

    /** What a force-delete removed, or would have removed when {@code dryRun} was asked for. */
    public record ForceDeleteResult(@NotNull List<String> deleted, boolean dryRun) {
        public ForceDeleteResult {
            deleted = deleted == null ? List.of() : List.copyOf(deleted);
        }
    }

    // -- Operations -------------------------------------------------------------------------

    /**
     * Starts a movement and returns the new operation's id.
     * <p>
     * That is all the server returns -- {@code {"id": "..."}} -- so anything else about the
     * operation has to be read back. The client's binding for this call deserializes the reply
     * into its full operation record, which is why every other field comes back null there.
     */
    @NotNull
    public static String start(
        @NotNull WeaviateDataSource ds,
        @NotNull String collection,
        @NotNull String shard,
        @NotNull String sourceNode,
        @NotNull String targetNode,
        @NotNull String type
    ) throws DBException {
        JsonObject payload = new JsonObject();
        payload.addProperty("collection", collection);
        payload.addProperty("shard", shard);
        payload.addProperty("sourceNode", sourceNode);
        payload.addProperty("targetNode", targetNode);
        payload.addProperty("type", type);
        String body = exchange(ds, "POST", BASE_PATH + "/replicate", payload.toString(),
            "start a " + type.toLowerCase(java.util.Locale.ROOT) + " of " + collection + "/" + shard,
            false);
        String id = str(JsonParser.parseString(body).getAsJsonObject(), "id");
        if (id == null) {
            throw new DBException("Weaviate accepted the request but returned no operation id");
        }
        return id;
    }

    /**
     * Every operation the server is tracking.
     * <p>
     * History is always requested. It is the difference between "this move is stuck" and "this
     * move has been retrying HYDRATING for ten minutes and here is what it says", and the list is
     * short enough that fetching it separately per row would cost more than it saves.
     */
    @NotNull
    public static List<OperationInfo> list(@NotNull WeaviateDataSource ds) throws DBException {
        String body = exchange(ds, "GET", BASE_PATH + "/replicate/list?includeHistory=true", null,
            "list replication operations", false);
        List<OperationInfo> result = new ArrayList<>();
        for (JsonElement e : array(body)) {
            OperationInfo op = parseOperation(e.getAsJsonObject());
            if (op != null) {
                result.add(op);
            }
        }
        return result;
    }

    /** One operation, or null when the server no longer has it. */
    @Nullable
    public static OperationInfo get(@NotNull WeaviateDataSource ds, @NotNull String id)
        throws DBException {
        String body = exchange(ds, "GET",
            BASE_PATH + "/replicate/" + segment(id) + "?includeHistory=true", null,
            "read replication operation " + id, true);
        return body == null ? null : parseOperation(JsonParser.parseString(body).getAsJsonObject());
    }

    /**
     * The replicas of one collection's shards.
     * <p>
     * {@code collection} is required despite the OpenAPI document marking it optional; omitting it
     * answers 400 {@code "collection is required"}, verified against 1.39.0.
     */
    @NotNull
    public static ShardingState shardingState(
        @NotNull WeaviateDataSource ds, @NotNull String collection
    ) throws DBException {
        String body = exchange(ds, "GET",
            BASE_PATH + "/sharding-state?collection=" + segment(collection), null,
            "read the sharding state of " + collection, false);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject state = root.has("shardingState") && root.get("shardingState").isJsonObject()
            ? root.getAsJsonObject("shardingState") : root;
        List<ShardReplicas> shards = new ArrayList<>();
        if (state.has("shards") && state.get("shards").isJsonArray()) {
            for (JsonElement e : state.getAsJsonArray("shards")) {
                JsonObject o = e.getAsJsonObject();
                List<String> replicas = new ArrayList<>();
                if (o.has("replicas") && o.get("replicas").isJsonArray()) {
                    for (JsonElement r : o.getAsJsonArray("replicas")) {
                        replicas.add(r.getAsString());
                    }
                }
                // "shard", as the server spells it. The client's record calls the field shardName
                // with no @SerializedName, which is why it is always null there.
                String name = str(o, "shard");
                shards.add(new ShardReplicas(name == null ? "" : name, replicas));
            }
        }
        String name = str(state, "collection");
        return new ShardingState(name == null ? collection : name, shards);
    }

    /** Asks the server to stop an operation. The record stays, as CANCELLED. */
    public static void cancel(@NotNull WeaviateDataSource ds, @NotNull String id)
        throws DBException {
        exchange(ds, "POST", BASE_PATH + "/replicate/" + segment(id) + "/cancel", "{}",
            "cancel replication operation " + id, false);
    }

    /** Cancels if needed, then removes the record. */
    public static void delete(@NotNull WeaviateDataSource ds, @NotNull String id)
        throws DBException {
        exchange(ds, "DELETE", BASE_PATH + "/replicate/" + segment(id), null,
            "delete replication operation " + id, false);
    }

    /** Removes every record the server will let go of, silently skipping the rest. */
    public static void deleteAll(@NotNull WeaviateDataSource ds) throws DBException {
        exchange(ds, "DELETE", BASE_PATH + "/replicate", null,
            "delete all replication operations", false);
    }

    /**
     * Rips operations out of the state machine with no checks and no cleanup.
     * <p>
     * The specification's own words are "USE AT OWN RISK ... may lead to data corruption or loss".
     * It exists because an operation can wedge in a state nothing else will move it out of, and it
     * is the one replication endpoint that still answers when the feature is switched off.
     * <p>
     * Always offer {@code dryRun} first: with it set the server reports which ids it <em>would</em>
     * remove and removes nothing.
     */
    @NotNull
    public static ForceDeleteResult forceDelete(
        @NotNull WeaviateDataSource ds,
        @Nullable String id,
        @Nullable String collection,
        @Nullable String shard,
        @Nullable String node,
        boolean dryRun
    ) throws DBException {
        JsonObject payload = new JsonObject();
        if (id != null && !id.isBlank()) {
            payload.addProperty("id", id);
        }
        if (collection != null && !collection.isBlank()) {
            payload.addProperty("collection", collection);
        }
        if (shard != null && !shard.isBlank()) {
            payload.addProperty("shard", shard);
        }
        if (node != null && !node.isBlank()) {
            payload.addProperty("node", node);
        }
        payload.addProperty("dryRun", dryRun);
        String body = exchange(ds, "POST", BASE_PATH + "/replicate/force-delete",
            payload.toString(), (dryRun ? "preview a force delete" : "force delete operations"),
            false);
        JsonObject o = JsonParser.parseString(body).getAsJsonObject();
        List<String> deleted = new ArrayList<>();
        if (o.has("deleted") && o.get("deleted").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("deleted")) {
                deleted.add(e.getAsString());
            }
        }
        return new ForceDeleteResult(deleted,
            o.has("dryRun") && !o.get("dryRun").isJsonNull() && o.get("dryRun").getAsBoolean());
    }

    // -- Parsing ----------------------------------------------------------------------------

    /**
     * One operation from its JSON.
     * <p>
     * The boolean flags are absent when false -- the server marks them {@code omitempty} -- so
     * they are read defensively rather than required.
     */
    @Nullable
    static OperationInfo parseOperation(@NotNull JsonObject o) {
        String id = str(o, "id");
        if (id == null) {
            return null;
        }
        List<StatusInfo> history = new ArrayList<>();
        if (o.has("statusHistory") && o.get("statusHistory").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("statusHistory")) {
                StatusInfo step = parseStatus(e.getAsJsonObject());
                if (step != null) {
                    history.add(step);
                }
            }
        }
        return new OperationInfo(
            id,
            orEmpty(str(o, "collection")),
            orEmpty(str(o, "shard")),
            orEmpty(str(o, "sourceNode")),
            orEmpty(str(o, "targetNode")),
            orEmpty(str(o, "type")),
            o.has("status") && o.get("status").isJsonObject()
                ? parseStatus(o.getAsJsonObject("status")) : null,
            history,
            bool(o, "uncancelable"),
            bool(o, "scheduledForCancel"),
            bool(o, "scheduledForDelete"),
            num(o, "whenStartedUnixMs"));
    }

    @Nullable
    static StatusInfo parseStatus(@NotNull JsonObject o) {
        String state = str(o, "state");
        List<ErrorInfo> errors = new ArrayList<>();
        if (o.has("errors") && o.get("errors").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("errors")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject error = e.getAsJsonObject();
                errors.add(new ErrorInfo(
                    num(error, "whenErroredUnixMs"), orEmpty(str(error, "message"))));
            }
        }
        return new StatusInfo(orEmpty(state), errors, num(o, "whenStartedUnixMs"));
    }

    @NotNull
    private static String orEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    @Nullable
    private static String str(@NotNull JsonObject o, @NotNull String field) {
        JsonElement e = o.get(field);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static boolean bool(@NotNull JsonObject o, @NotNull String field) {
        JsonElement e = o.get(field);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }

    private static long num(@NotNull JsonObject o, @NotNull String field) {
        JsonElement e = o.get(field);
        return e == null || e.isJsonNull() ? 0L : e.getAsLong();
    }

    @NotNull
    private static JsonArray array(@NotNull String body) throws DBException {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new DBException("Cannot read the replication response: " + e.getMessage(), e);
        }
    }

    @NotNull
    private static String segment(@NotNull String value) {
        return WeaviateSchemaRest.encodePathSegment(value);
    }

    // -- Transport --------------------------------------------------------------------------

    @Nullable
    private static String exchange(
        @NotNull WeaviateDataSource ds, @NotNull String method, @NotNull String path,
        @Nullable String body, @NotNull String what, boolean nullOn404
    ) throws DBException {
        URI uri = URI.create(ds.getRestBaseUrl() + path);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Accept", "application/json");
        if (body != null) {
            request.header("Content-Type", "application/json");
            request.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }

        String authorization = ds.getRestAuthorizationHeader();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            response = client.send(request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DBException("Cannot reach Weaviate at " + uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DBException("Interrupted while trying to " + what, e);
        }

        int status = response.statusCode();
        if (status == 501) {
            throw new DisabledException(
                "Replica movement is switched off on this server. Start every node with "
                    + "REPLICA_MOVEMENT_ENABLED=true to use it.");
        }
        if (status == 404 && nullOn404) {
            return null;
        }
        if (status < 200 || status >= 300) {
            throw new DBException(describe(status, response.body(), what));
        }
        return response.body();
    }

    /**
     * A refusal in the server's own words.
     * <p>
     * 409 is the one worth naming: it is what a cancel or delete gets when the operation has
     * passed the point where it can be stopped, and the server explains which.
     */
    @NotNull
    private static String describe(int status, @Nullable String body, @NotNull String what) {
        String message = WeaviateSchemaRest.extractErrorMessage(body);
        String prefix = "Cannot " + what + " (HTTP " + status;
        if (status == 409) {
            prefix += ", the operation is past the point where that is allowed";
        } else if (status == 403) {
            prefix += ", not permitted";
        }
        prefix += ")";
        if (message != null) {
            return prefix + ": " + message;
        }
        return body == null || body.isBlank() ? prefix : prefix + ": " + body.strip();
    }
}
