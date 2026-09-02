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

import io.weaviate.client6.v1.api.collections.query.QueryProfile;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * What the server did to answer the last query, per shard.
 * <p>
 * Produced only when asked for -- {@link WeaviateQuerySpec#isWithQueryProfile()} adds
 * {@code Metadata.QUERY_PROFILE} to the request. It costs nothing when off.
 * <p>
 * Plain-JDK all the way down because it crosses into the UI bundle, whose classloader cannot see
 * the shaded client.
 * <p>
 * <b>Two things the bundled client loses on the way here.</b> Its {@code ShardProfile} record has
 * one component, {@code searches}, while the wire carries {@code name} and {@code node} beside it
 * -- so every shard arrives anonymous, and a three-shard profile cannot say which node was slow.
 * The fields are kept here anyway, null for now: the wire has them, so a client fix fills them in
 * without this shape changing. And {@code GenerativeResponse} exposes no {@code queryProfile()}
 * at all, so a generative search cannot be profiled even though the reply carries one.
 */
public record WeaviateQueryProfile(@NotNull List<Shard> shards) {

    /**
     * One shard's work.
     *
     * @param name the shard, or null -- see the class comment
     * @param node the node it ran on, or null -- likewise
     */
    public record Shard(
        @Nullable String name,
        @Nullable String node,
        @NotNull List<Search> searches
    ) {
    }

    /**
     * One search within a shard, keyed by kind: {@code keyword}, {@code vector}, and both at once
     * for a hybrid.
     */
    public record Search(@NotNull String kind, @NotNull List<Detail> details) {
    }

    /** One measurement. Values arrive pre-formatted by the server, e.g. {@code 451.709µs}. */
    public record Detail(@NotNull String key, @NotNull String value) {
    }

    public boolean isEmpty() {
        return shards.isEmpty();
    }

    /**
     * Reads the client's profile into plain records.
     * <p>
     * Details are sorted by key, which is not merely tidy: the keyword pipeline numbers its own
     * keys -- {@code kwd_1_tok_time}, {@code kwd_2_terms_word}, {@code kwd_3_term_time} -- so
     * sorting renders the stages in the order the server ran them. The vector pipeline uses flat
     * names and simply reads alphabetically.
     */
    @Nullable
    public static WeaviateQueryProfile from(@Nullable QueryProfile profile) {
        if (profile == null || profile.shards() == null || profile.shards().isEmpty()) {
            return null;
        }
        List<Shard> shards = new ArrayList<>(profile.shards().size());
        for (QueryProfile.ShardProfile shard : profile.shards()) {
            Map<String, Map<String, String>> searches = shard.searches();
            if (searches == null) {
                continue;
            }
            List<Search> parsed = new ArrayList<>(searches.size());
            for (Map.Entry<String, Map<String, String>> entry : searches.entrySet()) {
                List<Detail> details = new ArrayList<>();
                if (entry.getValue() != null) {
                    for (Map.Entry<String, String> detail : entry.getValue().entrySet()) {
                        details.add(new Detail(detail.getKey(), String.valueOf(detail.getValue())));
                    }
                    details.sort(Comparator.comparing(Detail::key));
                }
                parsed.add(new Search(entry.getKey(), List.copyOf(details)));
            }
            parsed.sort(Comparator.comparing(Search::kind));
            shards.add(new Shard(null, null, List.copyOf(parsed)));
        }
        return shards.isEmpty() ? null : new WeaviateQueryProfile(List.copyOf(shards));
    }
}
