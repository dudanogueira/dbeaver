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

import io.weaviate.client6.v1.internal.grpc.protocol.WeaviateProtoSearchGet.MetadataResult;
import io.weaviate.client6.v1.internal.grpc.protocol.WeaviateProtoSearchGet.GroupByResult;
import io.weaviate.client6.v1.internal.grpc.protocol.WeaviateProtoSearchGet.SearchReply;
import io.weaviate.client6.v1.internal.grpc.protocol.WeaviateProtoSearchGet.SearchResult;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * Reading the rerank score out of a reply -- the one thing the typed client does not do.
 * <p>
 * Exercised against a hand-built reply rather than a live server: the plain and generative
 * search paths share this extraction, and a generative provider is not always reachable to
 * drive the second one end to end.
 */
public class WeaviateRerankSupportTest extends DBeaverUnitTest {

    private static SearchReply reply(SearchResult... results) {
        SearchReply.Builder b = SearchReply.newBuilder();
        for (SearchResult r : results) {
            b.addResults(r);
        }
        return b.build();
    }

    private static SearchResult result(String uuid, Double rerankScore) {
        MetadataResult.Builder md = MetadataResult.newBuilder().setId(uuid);
        if (rerankScore != null) {
            md.setRerankScore(rerankScore).setRerankScorePresent(true);
        }
        return SearchResult.newBuilder().setMetadata(md).build();
    }

    /** A grouped reply, whose objects hang off group_by_results instead of results. */
    private static SearchReply groupedReply(String groupName, SearchResult... results) {
        GroupByResult.Builder group = GroupByResult.newBuilder().setName(groupName);
        for (SearchResult r : results) {
            group.addObjects(r);
        }
        return SearchReply.newBuilder().addGroupByResults(group).build();
    }

    @Test
    public void collectsAScorePerObject() {
        Map<String, Float> scores = new HashMap<>();
        WeaviateRerankSupport.collectScores(
            reply(result("a", 0.781d), result("b", 0.052d)), scores);

        Assertions.assertEquals(2, scores.size());
        Assertions.assertEquals(0.781f, scores.get("a"), 0.0001f);
        Assertions.assertEquals(0.052f, scores.get("b"), 0.0001f);
    }

    /**
     * Zero is a real score, which is why the reply carries a separate present flag. Keying off
     * the value would drop it.
     */
    @Test
    public void keepsAZeroScore() {
        Map<String, Float> scores = new HashMap<>();
        WeaviateRerankSupport.collectScores(reply(result("a", 0d)), scores);
        Assertions.assertEquals(0f, scores.get("a"));
    }

    /** An unreranked reply carries no scores, and must not invent any. */
    @Test
    public void skipsObjectsWithoutAScore() {
        Map<String, Float> scores = new HashMap<>();
        WeaviateRerankSupport.collectScores(
            reply(result("a", null), result("b", 0.5d)), scores);
        Assertions.assertFalse(scores.containsKey("a"));
        Assertions.assertEquals(0.5f, scores.get("b"), 0.0001f);
    }

    @Test
    public void anEmptyReplyYieldsNoScores() {
        Map<String, Float> scores = new HashMap<>();
        WeaviateRerankSupport.collectScores(reply(), scores);
        Assertions.assertTrue(scores.isEmpty());
    }

    /**
     * Both search paths must be able to read the score; each resolves its own seam into the
     * client, and either could break independently on a client upgrade.
     */
    @Test
    public void bothSeamsResolveAgainstTheBundledClient() {
        Assertions.assertTrue(WeaviateRerankSupport.isAvailable(),
            "the plain search seam did not resolve");
        Assertions.assertTrue(WeaviateRerankSupport.isGenerativeAvailable(),
            "the generative search seam did not resolve");
    }

    /**
     * A grouped reply puts its objects under group_by_results and leaves results empty. Reading
     * only the flat list is why a grouped rerank first came back correctly ordered with every
     * score blank.
     */
    @Test
    public void collectsScoresFromAGroupedReply() {
        Map<String, Float> scores = new HashMap<>();
        WeaviateRerankSupport.collectScores(
            groupedReply("red", result("a", 0.9d), result("b", 0.4d)), scores);

        Assertions.assertEquals(2, scores.size(), "grouped objects were not read");
        Assertions.assertEquals(0.9f, scores.get("a"), 0.0001f);
        Assertions.assertEquals(0.4f, scores.get("b"), 0.0001f);
    }

    /**
     * One object can belong to two groups when grouping on an array property, so the same uuid
     * arrives twice. Scores are keyed by uuid, which is fine precisely because both copies carry
     * the same score -- but the second must not wipe the first out with an absent one.
     */
    @Test
    public void survivesAnObjectAppearingInTwoGroups() {
        Map<String, Float> scores = new HashMap<>();
        SearchReply reply = SearchReply.newBuilder()
            .addGroupByResults(GroupByResult.newBuilder().setName("alpha")
                .addObjects(result("shared", 0.75d)))
            .addGroupByResults(GroupByResult.newBuilder().setName("beta")
                .addObjects(result("shared", 0.75d)))
            .build();
        WeaviateRerankSupport.collectScores(reply, scores);

        Assertions.assertEquals(1, scores.size());
        Assertions.assertEquals(0.75f, scores.get("shared"), 0.0001f);
    }

    /** Both lists are read, so a reply that somehow carried each still yields both. */
    @Test
    public void readsBothListsWhenBothArePresent() {
        Map<String, Float> scores = new HashMap<>();
        SearchReply reply = SearchReply.newBuilder()
            .addResults(result("flat", 0.1d))
            .addGroupByResults(GroupByResult.newBuilder().setName("red")
                .addObjects(result("grouped", 0.2d)))
            .build();
        WeaviateRerankSupport.collectScores(reply, scores);

        Assertions.assertEquals(2, scores.size());
        Assertions.assertEquals(0.1f, scores.get("flat"), 0.0001f);
        Assertions.assertEquals(0.2f, scores.get("grouped"), 0.0001f);
    }
}
