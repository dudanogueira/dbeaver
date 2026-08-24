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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import io.weaviate.client6.v1.internal.json.JSON;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.utils.CommonUtils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Posts a collection definition to Weaviate's {@code /v1/schema} endpoint exactly as written.
 * <p>
 * The bundled client only offers {@code collections.create(CollectionConfig)}, which means parsing
 * the document into its object model and re-serializing it. That round trip is lossy - it drops
 * fields the client's version does not know about and fills in defaults for ones it does - so a
 * definition can reach the server looking different from what the user typed, and configurations
 * the client cannot model become impossible to create at all (a {@code dynamic} vector index is one
 * such case as of 6.3.1).
 * <p>
 * Sending the raw bytes avoids all of that: the server sees the user's document verbatim and its
 * response - success or refusal - is reported unchanged. Validation here is limited to "is this
 * JSON, and does it name a collection", because everything else is the server's judgement to make.
 */
public final class WeaviateSchemaRest {

    private static final Log log = Log.getLog(WeaviateSchemaRest.class);

    private static final String SCHEMA_PATH = "/v1/schema";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private WeaviateSchemaRest() {
    }

    /**
     * Create a collection from {@code rawJson}, sent byte-for-byte.
     *
     * @return the server's response body (the stored definition)
     * @throws DBException carrying the server's own explanation when it refuses
     */
    @NotNull
    public static String createCollection(
        @NotNull WeaviateDataSource dataSource,
        @NotNull String rawJson
    ) throws DBException {
        URI uri = URI.create(dataSource.getRestBaseUrl() + SCHEMA_PATH);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            // BodyPublishers.ofString with an explicit charset sends exactly these bytes -
            // no parsing, no re-encoding of the document itself.
            .POST(HttpRequest.BodyPublishers.ofString(rawJson, StandardCharsets.UTF_8));

        String authorization = dataSource.getRestAuthorizationHeader();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DBException("Cannot reach Weaviate at " + uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DBException("Interrupted while creating collection", e);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return CommonUtils.notEmpty(response.body());
        }
        throw new DBException(describeFailure(status, response.body()));
    }

    /**
     * Fetch a collection's definition exactly as the server stores it.
     * <p>
     * Read over REST rather than through {@code collections.getConfig} so the result includes every
     * field the server reports, not just the subset the client's object model can represent. That
     * is what lets the navigator show the whole definition.
     *
     * @return the raw response body
     */
    @NotNull
    public static String fetchCollectionSchema(
        @NotNull WeaviateDataSource dataSource,
        @NotNull String collectionName
    ) throws DBException {
        URI uri = URI.create(dataSource.getRestBaseUrl() + SCHEMA_PATH + "/"
            + URLEncoder.encode(collectionName, StandardCharsets.UTF_8));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .GET();

        String authorization = dataSource.getRestAuthorizationHeader();
        if (authorization != null) {
            request.header("Authorization", authorization);
        }

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new DBException("Cannot read schema for '" + collectionName + "': " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DBException("Interrupted while reading collection schema", e);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return CommonUtils.notEmpty(response.body());
        }
        String message = extractErrorMessage(response.body());
        throw new DBException("Cannot read schema for '" + collectionName + "' (HTTP " + status + ")"
            + (message == null ? "" : ": " + message));
    }

    /**
     * Turn an error response into the clearest sentence available.
     * <p>
     * Weaviate reports refusals as {@code {"error":[{"message":"..."}]}}. That message is the whole
     * value of this call - it is what says, for instance, that a dynamic index needs async indexing
     * enabled - so it is extracted and shown on its own rather than buried in a raw body dump.
     */
    @NotNull
    private static String describeFailure(int status, @Nullable String body) {
        String message = extractErrorMessage(body);
        if (message != null) {
            return "Weaviate rejected the collection (HTTP " + status + "): " + message;
        }
        if (CommonUtils.isEmptyTrimmed(body)) {
            return "Weaviate rejected the collection (HTTP " + status + ")";
        }
        return "Weaviate rejected the collection (HTTP " + status + "): " + body.strip();
    }

    @Nullable
    private static String extractErrorMessage(@Nullable String body) {
        if (CommonUtils.isEmptyTrimmed(body)) {
            return null;
        }
        try {
            JsonElement root = JSON.toJsonElement(body);
            if (root == null || !root.isJsonObject()) {
                return null;
            }
            JsonElement error = root.getAsJsonObject().get("error");
            if (error == null) {
                return null;
            }
            if (error.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement item : error.getAsJsonArray()) {
                    String text = messageOf(item);
                    if (text != null) {
                        if (sb.length() > 0) {
                            sb.append("; ");
                        }
                        sb.append(text);
                    }
                }
                return sb.length() == 0 ? null : sb.toString();
            }
            return messageOf(error);
        } catch (JsonParseException | IllegalStateException e) {
            log.debug("Unparseable error body from Weaviate", e);
            return null;
        }
    }

    @Nullable
    private static String messageOf(@Nullable JsonElement element) {
        if (element == null) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement message = object.get("message");
            if (message != null && message.isJsonPrimitive()) {
                return message.getAsString();
            }
        }
        return null;
    }
}
