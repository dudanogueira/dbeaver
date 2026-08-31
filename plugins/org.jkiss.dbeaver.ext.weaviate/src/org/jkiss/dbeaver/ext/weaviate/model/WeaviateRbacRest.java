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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Weaviate's RBAC API, over REST.
 * <p>
 * Not through the bundled client, and not as a matter of taste: {@code client.roles.list()} --
 * the first call any RBAC screen makes -- <em>throws</em> against any server from 1.38 on.
 * Measured against the lab's 1.39.0:
 * <pre>
 * roles.list()         IllegalArgumentException: Permission$Kind does not have a member
 *                      with jsonValue=namespaces
 * roles.get("admin")   the same
 * roles.get("viewer")  fine, 11 permissions
 * </pre>
 * The cause is a closed enum. {@code Permission.Kind} knows twelve permission kinds and the
 * server has grown a thirteenth, {@code namespaces}; {@code JsonEnum.valueOfJson} throws on a
 * name it does not hold rather than degrading. Every server since 1.38 puts
 * {@code manage_namespaces} into the built-in {@code admin} and {@code root} roles, so the failure
 * is not an edge case -- it is every cluster, on the first request. {@code viewer} survives only
 * because it keeps read actions alone.
 * <p>
 * So nothing here is typed against a fixed vocabulary. An action is a {@link String} and a
 * permission's scope is a {@link Map}, which means a permission this plugin has never heard of is
 * still listed, still described, and -- because the role editor saves by diffing -- still
 * preserved through an edit that touches something else. A newer server degrades to "shown but not
 * editable" instead of an exception.
 */
public final class WeaviateRbacRest {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private WeaviateRbacRest() {
    }

    // -- Value types ------------------------------------------------------------------------
    // Plain JDK, like WeaviateTenant and WeaviateNodesRest's records: the UI bundle's classloader
    // cannot see anything from the shaded jar, so these are what cross into a dialog.

    /**
     * One permission: an action, and at most one scope.
     * <p>
     * The wire form is an action plus <em>one</em> sub-object naming what it applies to --
     * {@code {"action":"read_data","data":{"collection":"*","tenant":"*"}}}. That sub-object's key
     * is the kind and its fields are the scope, and both are kept as text so an unrecognised kind
     * survives. A few actions ({@code read_cluster}, the {@code *_mcp} family) carry no sub-object
     * at all, and then {@code kind} is null.
     */
    public record PermissionInfo(
        @NotNull String action,
        @Nullable String kind,
        @NotNull Map<String, String> scope
    ) {
        public PermissionInfo {
            scope = scope == null ? Map.of() : Map.copyOf(scope);
        }

        /**
         * A canonical string identifying this permission, for diffing one role against another.
         * <p>
         * Sorted scope keys, so two permissions that mean the same thing compare equal whatever
         * order the server or the editor happened to produce their fields in. The role editor
         * saves by computing which permissions were added and removed, and that is only correct
         * if equality here is about meaning rather than about JSON layout.
         */
        @NotNull
        public String key() {
            StringBuilder sb = new StringBuilder(action);
            sb.append('|').append(kind == null ? "" : kind);
            for (Map.Entry<String, String> e : new TreeMap<>(scope).entrySet()) {
                sb.append('|').append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }

        /** The scope as {@code collection=Foo, tenant=bar}, for a label or a tooltip. */
        @NotNull
        public String describeScope() {
            if (scope.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : new TreeMap<>(scope).entrySet()) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(e.getKey()).append('=').append(e.getValue());
            }
            return sb.toString();
        }
    }

    public record RoleInfo(@NotNull String name, @NotNull List<PermissionInfo> permissions) {
        public RoleInfo {
            permissions = permissions == null ? List.of() : List.copyOf(permissions);
        }
    }

    /**
     * A database user.
     *
     * @param dbUserType {@code db_user} for one created through the API, {@code db_env_user} for
     *                   one declared in the server's environment. The distinction matters: an
     *                   env user cannot be edited, rotated or deleted, and the client's own
     *                   {@code UserType} loses it entirely by declaring
     *                   {@code @SerializedName("db")} on two constants.
     */
    public record DbUserInfo(
        @NotNull String userId,
        @Nullable String dbUserType,
        boolean active,
        @NotNull List<String> roles,
        @Nullable String apiKeyFirstLetters,
        @Nullable String createdAt,
        @Nullable String lastUsedAt
    ) {
        public DbUserInfo {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }

        public boolean isEnvUser() {
            return "db_env_user".equals(dbUserType);
        }
    }

    public record UserAssignment(@NotNull String userId, @Nullable String userType) {
    }

    public record GroupAssignment(@NotNull String groupId, @Nullable String groupType) {
    }

    /** {@code /users/own-info}: who the connection is authenticated as. */
    public record OwnInfo(
        @Nullable String username,
        @Nullable List<RoleInfo> roles,
        @Nullable List<String> groups
    ) {
        /**
         * Whether the server looks like it has RBAC switched on.
         * <p>
         * There is no capability flag anywhere -- {@code /v1/meta} carries only hostname, version,
         * modules and a gRPC size, and the authz handlers are registered whether or not RBAC is
         * enabled, so every endpoint answers 200 with an empty body when it is off. The one signal
         * that distinguishes "disabled" from "nothing defined yet" is this: with RBAC on, the
         * caller always resolves to at least one role, and with it off {@code roles} is null.
         */
        public boolean rbacEnabled() {
            return roles != null;
        }
    }

    // -- Roles ------------------------------------------------------------------------------

    @NotNull
    public static List<RoleInfo> listRoles(@NotNull WeaviateDataSource ds) throws DBException {
        return parseRoles(get(ds, "/v1/authz/roles", "list roles"));
    }

    @Nullable
    public static RoleInfo getRole(@NotNull WeaviateDataSource ds, @NotNull String role)
        throws DBException {
        String body = getAllowingNotFound(ds,
            "/v1/authz/roles/" + segment(role), "read role " + role);
        if (body == null) {
            return null;
        }
        return parseRole(JsonParser.parseString(body).getAsJsonObject());
    }

    public static void createRole(
        @NotNull WeaviateDataSource ds, @NotNull String role,
        @NotNull List<PermissionInfo> permissions
    ) throws DBException {
        JsonObject payload = new JsonObject();
        payload.addProperty("name", role);
        payload.add("permissions", toJson(permissions));
        send(ds, "POST", "/v1/authz/roles", payload.toString(), "create role " + role);
    }

    public static void deleteRole(@NotNull WeaviateDataSource ds, @NotNull String role)
        throws DBException {
        send(ds, "DELETE", "/v1/authz/roles/" + segment(role), null, "delete role " + role);
    }

    public static void addPermissions(
        @NotNull WeaviateDataSource ds, @NotNull String role,
        @NotNull List<PermissionInfo> permissions
    ) throws DBException {
        if (permissions.isEmpty()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("name", role);
        payload.add("permissions", toJson(permissions));
        send(ds, "POST", "/v1/authz/roles/" + segment(role) + "/add-permissions",
            payload.toString(), "add permissions to " + role);
    }

    public static void removePermissions(
        @NotNull WeaviateDataSource ds, @NotNull String role,
        @NotNull List<PermissionInfo> permissions
    ) throws DBException {
        if (permissions.isEmpty()) {
            return;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("name", role);
        payload.add("permissions", toJson(permissions));
        send(ds, "POST", "/v1/authz/roles/" + segment(role) + "/remove-permissions",
            payload.toString(), "remove permissions from " + role);
    }

    /**
     * Which users hold this role.
     * <p>
     * {@code /user-assignments} rather than the older {@code /authz/roles/{id}/users}, which the
     * client still calls and which answers <b>410 Gone</b> on a namespace-enabled cluster.
     */
    @NotNull
    public static List<UserAssignment> roleUserAssignments(
        @NotNull WeaviateDataSource ds, @NotNull String role
    ) throws DBException {
        List<UserAssignment> result = new ArrayList<>();
        for (JsonElement e : array(get(ds,
            "/v1/authz/roles/" + segment(role) + "/user-assignments",
            "read user assignments of " + role))) {
            JsonObject o = e.getAsJsonObject();
            result.add(new UserAssignment(str(o, "userId"), str(o, "userType")));
        }
        return result;
    }

    @NotNull
    public static List<GroupAssignment> roleGroupAssignments(
        @NotNull WeaviateDataSource ds, @NotNull String role
    ) throws DBException {
        List<GroupAssignment> result = new ArrayList<>();
        for (JsonElement e : array(get(ds,
            "/v1/authz/roles/" + segment(role) + "/group-assignments",
            "read group assignments of " + role))) {
            JsonObject o = e.getAsJsonObject();
            result.add(new GroupAssignment(str(o, "groupId"), str(o, "groupType")));
        }
        return result;
    }

    // -- Users ------------------------------------------------------------------------------

    @NotNull
    public static List<DbUserInfo> listDbUsers(@NotNull WeaviateDataSource ds) throws DBException {
        List<DbUserInfo> result = new ArrayList<>();
        for (JsonElement e : array(get(ds,
            "/v1/users/db?includeLastUsedTime=true", "list database users"))) {
            result.add(parseUser(e.getAsJsonObject()));
        }
        return result;
    }

    /** The API key, which the server returns exactly once and never again. */
    @NotNull
    public static String createDbUser(@NotNull WeaviateDataSource ds, @NotNull String userId)
        throws DBException {
        String body = send(ds, "POST", "/v1/users/db/" + segment(userId), "{}",
            "create user " + userId);
        String key = str(JsonParser.parseString(body).getAsJsonObject(), "apikey");
        if (key == null) {
            throw new DBException("Weaviate created " + userId + " but returned no API key");
        }
        return key;
    }

    public static void deleteDbUser(@NotNull WeaviateDataSource ds, @NotNull String userId)
        throws DBException {
        send(ds, "DELETE", "/v1/users/db/" + segment(userId), null, "delete user " + userId);
    }

    public static void activateDbUser(@NotNull WeaviateDataSource ds, @NotNull String userId)
        throws DBException {
        send(ds, "POST", "/v1/users/db/" + segment(userId) + "/activate", "{}",
            "activate user " + userId);
    }

    public static void deactivateDbUser(
        @NotNull WeaviateDataSource ds, @NotNull String userId, boolean revokeKey
    ) throws DBException {
        send(ds, "POST", "/v1/users/db/" + segment(userId) + "/deactivate",
            "{\"revoke_key\":" + revokeKey + "}", "deactivate user " + userId);
    }

    @NotNull
    public static String rotateDbUserKey(@NotNull WeaviateDataSource ds, @NotNull String userId)
        throws DBException {
        String body = send(ds, "POST", "/v1/users/db/" + segment(userId) + "/rotate-key", "{}",
            "rotate the API key of " + userId);
        String key = str(JsonParser.parseString(body).getAsJsonObject(), "apikey");
        if (key == null) {
            throw new DBException("Weaviate rotated the key of " + userId + " but returned none");
        }
        return key;
    }

    @NotNull
    public static List<RoleInfo> userRoles(
        @NotNull WeaviateDataSource ds, @NotNull String userId, @NotNull String userType
    ) throws DBException {
        return parseRoles(get(ds,
            "/v1/authz/users/" + segment(userId) + "/roles/" + segment(userType),
            "read the roles of " + userId));
    }

    public static void assignRolesToUser(
        @NotNull WeaviateDataSource ds, @NotNull String userId,
        @NotNull List<String> roles, @NotNull String userType
    ) throws DBException {
        if (roles.isEmpty()) {
            return;
        }
        send(ds, "POST", "/v1/authz/users/" + segment(userId) + "/assign",
            rolesPayload(roles, "userType", userType), "assign roles to " + userId);
    }

    public static void revokeRolesFromUser(
        @NotNull WeaviateDataSource ds, @NotNull String userId,
        @NotNull List<String> roles, @NotNull String userType
    ) throws DBException {
        if (roles.isEmpty()) {
            return;
        }
        send(ds, "POST", "/v1/authz/users/" + segment(userId) + "/revoke",
            rolesPayload(roles, "userType", userType), "revoke roles from " + userId);
    }

    // -- Groups -----------------------------------------------------------------------------

    /** Group ids the server has seen. OIDC is the only group type Weaviate accepts today. */
    @NotNull
    public static List<String> listOidcGroups(@NotNull WeaviateDataSource ds) throws DBException {
        List<String> result = new ArrayList<>();
        for (JsonElement e : array(get(ds, "/v1/authz/groups/oidc", "list OIDC groups"))) {
            result.add(e.getAsString());
        }
        return result;
    }

    @NotNull
    public static List<RoleInfo> groupRoles(@NotNull WeaviateDataSource ds, @NotNull String groupId)
        throws DBException {
        return parseRoles(get(ds,
            "/v1/authz/groups/" + segment(groupId) + "/roles/oidc",
            "read the roles of group " + groupId));
    }

    public static void assignRolesToGroup(
        @NotNull WeaviateDataSource ds, @NotNull String groupId, @NotNull List<String> roles
    ) throws DBException {
        if (roles.isEmpty()) {
            return;
        }
        send(ds, "POST", "/v1/authz/groups/" + segment(groupId) + "/assign",
            rolesPayload(roles, "groupType", "oidc"), "assign roles to group " + groupId);
    }

    public static void revokeRolesFromGroup(
        @NotNull WeaviateDataSource ds, @NotNull String groupId, @NotNull List<String> roles
    ) throws DBException {
        if (roles.isEmpty()) {
            return;
        }
        send(ds, "POST", "/v1/authz/groups/" + segment(groupId) + "/revoke",
            rolesPayload(roles, "groupType", "oidc"), "revoke roles from group " + groupId);
    }

    // -- Own info ---------------------------------------------------------------------------

    @NotNull
    public static OwnInfo ownInfo(@NotNull WeaviateDataSource ds) throws DBException {
        JsonObject o = JsonParser.parseString(get(ds, "/v1/users/own-info", "read own info"))
            .getAsJsonObject();
        List<RoleInfo> roles = null;
        if (o.has("roles") && o.get("roles").isJsonArray()) {
            roles = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("roles")) {
                roles.add(parseRole(e.getAsJsonObject()));
            }
        }
        List<String> groups = null;
        if (o.has("groups") && o.get("groups").isJsonArray()) {
            groups = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("groups")) {
                groups.add(e.getAsString());
            }
        }
        return new OwnInfo(str(o, "username"), roles, groups);
    }

    // -- Parsing ----------------------------------------------------------------------------

    @NotNull
    private static List<RoleInfo> parseRoles(@NotNull String body) throws DBException {
        List<RoleInfo> result = new ArrayList<>();
        for (JsonElement e : array(body)) {
            result.add(parseRole(e.getAsJsonObject()));
        }
        return result;
    }

    @NotNull
    static RoleInfo parseRole(@NotNull JsonObject o) {
        List<PermissionInfo> permissions = new ArrayList<>();
        if (o.has("permissions") && o.get("permissions").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("permissions")) {
                PermissionInfo permission = parsePermission(e.getAsJsonObject());
                if (permission != null) {
                    permissions.add(permission);
                }
            }
        }
        String name = str(o, "name");
        return new RoleInfo(name == null ? "" : name, permissions);
    }

    /**
     * One permission, taking whichever key is not {@code action} as the kind.
     * <p>
     * The kind is read from the payload rather than matched against a list, which is the whole
     * reason this class exists rather than a call to the client.
     */
    @Nullable
    static PermissionInfo parsePermission(@NotNull JsonObject o) {
        String action = str(o, "action");
        if (action == null) {
            return null;
        }
        for (String key : o.keySet()) {
            if ("action".equals(key) || !o.get(key).isJsonObject()) {
                continue;
            }
            Map<String, String> scope = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> field : o.getAsJsonObject(key).entrySet()) {
                if (!field.getValue().isJsonNull()) {
                    scope.put(field.getKey(), field.getValue().getAsString());
                }
            }
            return new PermissionInfo(action, key, scope);
        }
        // read_cluster and the *_mcp actions are scopeless, and arrive as {"action": "..."} alone.
        return new PermissionInfo(action, null, Map.of());
    }

    @NotNull
    static DbUserInfo parseUser(@NotNull JsonObject o) {
        List<String> roles = new ArrayList<>();
        if (o.has("roles") && o.get("roles").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("roles")) {
                roles.add(e.isJsonObject() ? str(e.getAsJsonObject(), "name") : e.getAsString());
            }
        }
        String userId = str(o, "userId");
        return new DbUserInfo(
            userId == null ? "" : userId,
            str(o, "dbUserType"),
            !o.has("active") || o.get("active").isJsonNull() || o.get("active").getAsBoolean(),
            roles,
            str(o, "apiKeyFirstLetters"),
            str(o, "createdAt"),
            str(o, "lastUsedAt"));
    }

    @NotNull
    private static JsonArray toJson(@NotNull List<PermissionInfo> permissions) {
        JsonArray array = new JsonArray();
        for (PermissionInfo permission : permissions) {
            JsonObject o = new JsonObject();
            o.addProperty("action", permission.action());
            if (permission.kind() != null && !permission.scope().isEmpty()) {
                JsonObject scope = new JsonObject();
                permission.scope().forEach(scope::addProperty);
                o.add(permission.kind(), scope);
            }
            array.add(o);
        }
        return array;
    }

    @NotNull
    private static String rolesPayload(
        @NotNull List<String> roles, @NotNull String typeField, @NotNull String typeValue
    ) {
        JsonObject payload = new JsonObject();
        JsonArray array = new JsonArray();
        roles.forEach(array::add);
        payload.add("roles", array);
        payload.addProperty(typeField, typeValue);
        return payload.toString();
    }

    @NotNull
    private static JsonArray array(@NotNull String body) throws DBException {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
        } catch (JsonSyntaxException | IllegalStateException e) {
            throw new DBException("Cannot read the RBAC response: " + e.getMessage(), e);
        }
    }

    @Nullable
    private static String str(@NotNull JsonObject o, @NotNull String field) {
        JsonElement e = o.get(field);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    @NotNull
    private static String segment(@NotNull String value) {
        return WeaviateSchemaRest.encodePathSegment(value);
    }

    // -- Transport --------------------------------------------------------------------------

    @NotNull
    private static String get(
        @NotNull WeaviateDataSource ds, @NotNull String path, @NotNull String what
    ) throws DBException {
        String body = exchange(ds, "GET", path, null, what, false);
        return body == null ? "" : body;
    }

    @Nullable
    private static String getAllowingNotFound(
        @NotNull WeaviateDataSource ds, @NotNull String path, @NotNull String what
    ) throws DBException {
        return exchange(ds, "GET", path, null, what, true);
    }

    @NotNull
    private static String send(
        @NotNull WeaviateDataSource ds, @NotNull String method, @NotNull String path,
        @Nullable String body, @NotNull String what
    ) throws DBException {
        String response = exchange(ds, method, path, body, what, false);
        return response == null ? "" : response;
    }

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
     * RBAC endpoints refuse for reasons the user can act on -- a built-in role cannot be edited, a
     * user name collides with a root user, db user management is switched off -- and the server
     * says which. A bare status code says none of it.
     */
    @NotNull
    private static String describe(int status, @Nullable String body, @NotNull String what) {
        String message = WeaviateSchemaRest.extractErrorMessage(body);
        String prefix = "Cannot " + what + " (HTTP " + status;
        if (status == 403) {
            prefix += ", not permitted";
        }
        prefix += ")";
        if (message != null) {
            return prefix + ": " + message;
        }
        return body == null || body.isBlank() ? prefix : prefix + ": " + body.strip();
    }
}
