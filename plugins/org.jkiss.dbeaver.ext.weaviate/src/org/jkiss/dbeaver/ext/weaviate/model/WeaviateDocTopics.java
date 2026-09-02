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

import java.util.Map;
import java.util.Set;

/**
 * Maps a navigator folder to the Weaviate documentation page that explains it.
 * <p>
 * Keys are the {@code id} attributes of {@code <folder>} elements in {@code plugin.xml} --
 * mostly the ones under a collection, plus the connection-wide Aliases folder. They are declared explicitly there so this map
 * does not depend on {@code DBXTreeFolder.getHumanReadableId()}'s fallback of joining
 * child {@code <items path=...>} values, which would change if a path were renamed.
 * {@code WeaviateDocTopicsTest} asserts that the two sets stay in sync.
 * <p>
 * Most entries deep-link into the single collection-definition reference page. Three
 * folders -- rerankers, generative and object TTL -- are not covered by that page at all,
 * so they point at the closest concept page instead.
 */
public class WeaviateDocTopics {

    private static final String DOCS_BASE = "https://docs.weaviate.io";

    private static final String COLLECTION_CONFIG = DOCS_BASE + "/weaviate/config-refs/collections";

    private static final Map<String, String> TOPIC_URLS = Map.ofEntries(
        Map.entry("definition", COLLECTION_CONFIG),
        Map.entry("properties", COLLECTION_CONFIG + "#properties"),
        Map.entry("vectorizers", COLLECTION_CONFIG + "#vector-configuration"),
        Map.entry("replication", COLLECTION_CONFIG + "#replication"),
        Map.entry("sharding", COLLECTION_CONFIG + "#sharding"),
        Map.entry("multiTenancy", COLLECTION_CONFIG + "#multi-tenancy"),
        Map.entry("invertedIndex", COLLECTION_CONFIG + "#inverted-index"),
        // Not documented in the collection-definition reference.
        Map.entry("rerankers", DOCS_BASE + "/weaviate/concepts/reranking"),
        Map.entry("objectTtl", DOCS_BASE + "/weaviate/concepts/data#time-to-live-ttl"),
        Map.entry("generative", DOCS_BASE + "/weaviate/model-providers"),
        // The two Aliases folders -- the connection-wide one and a collection's own -- share an
        // id-less topic because they explain the same thing from either end.
        Map.entry("aliases", DOCS_BASE + "/weaviate/manage-collections/collection-aliases"),
        Map.entry("collectionAliases", DOCS_BASE + "/weaviate/manage-collections/collection-aliases"));

    private WeaviateDocTopics() {
        // Utility class
    }

    /**
     * Documentation URL for a navigator folder id, or null when the folder has no
     * documentation topic -- which is how the context menu decides not to show itself.
     */
    @Nullable
    public static String urlFor(@Nullable String folderId) {
        return folderId == null ? null : TOPIC_URLS.get(folderId);
    }

    /**
     * The folder ids this map covers. Exposed for tests.
     */
    @NotNull
    public static Set<String> topicIds() {
        return TOPIC_URLS.keySet();
    }
}
