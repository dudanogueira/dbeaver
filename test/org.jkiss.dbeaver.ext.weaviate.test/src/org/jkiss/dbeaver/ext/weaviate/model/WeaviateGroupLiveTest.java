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

import io.weaviate.client6.v1.api.Authentication;
import io.weaviate.client6.v1.api.WeaviateClient;
import io.weaviate.client6.v1.api.collections.query.GroupBy;
import io.weaviate.client6.v1.api.collections.query.Metadata;
import io.weaviate.client6.v1.api.collections.query.QueryObjectGrouped;
import io.weaviate.client6.v1.api.collections.query.QueryResponseGroup;
import io.weaviate.client6.v1.api.collections.query.QueryResponseGrouped;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Runs group-by against a real Weaviate and asserts what comes back.
 * <p>
 * Grouping has several behaviours nobody would guess correctly -- what happens to an object with
 * no value for the group key, whether the reported per-group count is the group's real size or
 * the capped one, whether an object in two groups arrives twice -- and each of them changes how
 * a grid row should be built. This pins them down against the server rather than against a
 * reading of the docs.
 * <p>
 * Skipped unless {@code WEAVIATE_GROUP_FIXTURE_URL} is set, so the reactor stays hermetic. Seed
 * the data first with {@code testdata/seed_group_fixture.py}, then:
 * <pre>
 * WEAVIATE_GROUP_FIXTURE_URL=http://localhost:8080 mvn verify ...
 * </pre>
 */
public class WeaviateGroupLiveTest extends DBeaverUnitTest {

    private static final String COLLECTION = "DBeaverGroupFixture";

    /** The origin. Under squared L2 this ranks the fixture rows by their rank property. */
    private static final float[] PROBE = {0.0f, 0.0f};

    /**
     * Skip unless a fixture server is configured. Called first in every test rather than from
     * the query helper: an abort raised deeper down would be caught by the helper's own error
     * handling and reported as a failure instead of a skip.
     */
    private static void requireFixture() {
        String url = System.getenv("WEAVIATE_GROUP_FIXTURE_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
            "set WEAVIATE_GROUP_FIXTURE_URL to run the live group-by tests");
    }

    private static WeaviateClient connect() {
        String url = System.getenv("WEAVIATE_GROUP_FIXTURE_URL");
        String host = url.replaceFirst("^https?://", "").replaceFirst(":.*$", "");
        String apiKey = System.getenv().getOrDefault("WEAVIATE_API_KEY", "root-user-key");
        return WeaviateClient.connectToCustom(c -> {
            c.scheme("http");
            c.httpHost(host);
            c.httpPort(8080);
            c.grpcHost(host);
            c.grpcPort(50051);
            c.authentication(Authentication.apiKey(apiKey));
            return c;
        });
    }

    private static QueryResponseGrouped<Map<String, Object>> grouped(
        Function<io.weaviate.client6.v1.api.collections.query.WeaviateQueryClient<Map<String, Object>>,
            QueryResponseGrouped<Map<String, Object>>> call
    ) {
        try (WeaviateClient client = connect()) {
            return call.apply(client.collections.use(COLLECTION).query);
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            throw new IllegalStateException("live group-by query failed: " + cause.getMessage(), e);
        }
    }

    private static QueryResponseGrouped<Map<String, Object>> nearVectorGrouped(
        String property, int maxGroups, int perGroup
    ) {
        GroupBy groupBy = GroupBy.property(property, maxGroups, perGroup);
        return grouped(q -> q.nearVector(PROBE, groupBy));
    }

    /** Group name -> titles, in the order the server returned them. */
    private static Map<String, List<String>> titlesByGroup(
        QueryResponseGrouped<Map<String, Object>> response
    ) {
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        for (QueryObjectGrouped<Map<String, Object>> obj : response.objects()) {
            byGroup.computeIfAbsent(obj.belongsToGroup(), k -> new ArrayList<>())
                .add(String.valueOf(obj.properties().get("title")));
        }
        return byGroup;
    }

    @Test
    public void groupsAreReportedWithTheirMembers() {
        requireFixture();
        QueryResponseGrouped<Map<String, Object>> response =
            nearVectorGrouped("category", 10, 10);

        Map<String, List<String>> byGroup = titlesByGroup(response);
        Assertions.assertEquals(List.of("red-near", "red-mid", "red-far"), byGroup.get("red"));
        Assertions.assertEquals(List.of("green-near", "green-far"), byGroup.get("green"));
        Assertions.assertEquals(List.of("blue-only"), byGroup.get("blue"));

        // The flat list and the group map describe the same objects; the panel builds rows from
        // the flat one and joins the map on, so a disagreement would show up as blank columns.
        int inGroups = response.groups().values().stream()
            .mapToInt(g -> g.objects().size()).sum();
        Assertions.assertEquals(response.objects().size(), inGroups);
    }

    /**
     * An object with no value for the group key is not dropped -- it lands in a group named with
     * the empty string. Worth pinning: a null here would need different row handling, and it is
     * the sort of thing that changes between versions.
     */
    @Test
    public void anUnsetGroupKeyBecomesTheEmptyStringGroup() {
        requireFixture();
        Map<String, List<String>> byGroup = titlesByGroup(nearVectorGrouped("category", 10, 10));

        Assertions.assertTrue(byGroup.containsKey(""),
            () -> "expected an empty-named group, got " + byGroup.keySet());
        Assertions.assertEquals(List.of("no-category"), byGroup.get(""));
    }

    /** The cap keeps the best-ranked groups, so groups are ordered by their nearest member. */
    @Test
    public void maxGroupsKeepsTheNearestGroups() {
        requireFixture();
        Map<String, List<String>> byGroup = titlesByGroup(nearVectorGrouped("category", 2, 10));

        Assertions.assertEquals(2, byGroup.size(),
            () -> "expected two groups, got " + byGroup.keySet());
        Assertions.assertEquals(List.of("red", "green"), new ArrayList<>(byGroup.keySet()));
    }

    /** Independent of the group cap: the groups all survive, each trimmed to one object. */
    @Test
    public void objectsPerGroupTruncatesWithinEachGroup() {
        requireFixture();
        Map<String, List<String>> byGroup = titlesByGroup(nearVectorGrouped("category", 10, 1));

        Assertions.assertEquals(List.of("red-near"), byGroup.get("red"));
        Assertions.assertEquals(List.of("green-near"), byGroup.get("green"));
        Assertions.assertEquals(List.of("blue-only"), byGroup.get("blue"));
    }

    /**
     * numberOfObjects counts what came back, not what the group holds: capped at one object per
     * group, red reports 1 even though three rows match. So it cannot be presented as a group
     * total -- which is why the column is named a count and is opt-in.
     */
    @Test
    public void theGroupCountReflectsTheCapNotTheTrueSize() {
        requireFixture();
        QueryResponseGroup<Map<String, Object>> uncapped =
            nearVectorGrouped("category", 10, 10).groups().get("red");
        QueryResponseGroup<Map<String, Object>> capped =
            nearVectorGrouped("category", 10, 1).groups().get("red");

        Assertions.assertEquals(3L, uncapped.numberOfObjects());
        Assertions.assertEquals(1L, capped.numberOfObjects(),
            "if this now reports 3, the count became a true group total and the column's "
                + "tooltip and docs should say so");
    }

    /**
     * Grouping on an array property puts one object in several groups, so the same uuid arrives
     * more than once. The grid shows it as one row per group, which is the honest rendering.
     */
    @Test
    public void anObjectCanBelongToSeveralGroups() {
        requireFixture();
        QueryResponseGrouped<Map<String, Object>> response = nearVectorGrouped("labels", 10, 10);
        Map<String, List<String>> byGroup = titlesByGroup(response);

        Assertions.assertEquals(List.of("red-near", "red-mid", "green-near"), byGroup.get("alpha"));
        Assertions.assertEquals(List.of("red-near", "red-far"), byGroup.get("beta"));
        Assertions.assertEquals(List.of("blue-only"), byGroup.get("gamma"));

        long redNearRows = response.objects().stream()
            .filter(o -> "red-near".equals(o.properties().get("title")))
            .count();
        Assertions.assertEquals(2, redNearRows, "an object in two groups should yield two rows");
    }

    /** An empty array groups the same way an unset value does. */
    @Test
    public void anEmptyArrayGroupsUnderTheEmptyString() {
        requireFixture();
        Map<String, List<String>> byGroup = titlesByGroup(nearVectorGrouped("labels", 10, 10));
        Assertions.assertEquals(List.of("green-far", "no-category"), byGroup.get(""));
    }

    /**
     * The client declares {@code fetchObjects(GroupBy)} and it looks like the sixth supported
     * mode. The server refuses it: grouping orders its groups by their best-ranked member, and a
     * plain fetch has no ranking to do that with.
     * <p>
     * This is why {@code WeaviateQueryMode.supportsGroupBy()} exists and why the panel hides the
     * section under fetch. It also matters for a remembered search: demoting a grouped near-text
     * to fetch when a viewer reopens would otherwise send a grouped fetch and fail instead of
     * quietly browsing.
     * <p>
     * If this ever starts passing, the server gained the combination -- drop the mode predicate
     * and let the panel offer it.
     */
    @Test
    public void groupingIsRefusedOnAPlainFetch() {
        requireFixture();
        GroupBy groupBy = GroupBy.property("category", 10, 10);

        IllegalStateException e = Assertions.assertThrows(IllegalStateException.class,
            () -> grouped(q -> q.fetchObjects(b -> b.limit(50), groupBy)),
            "a grouped fetch now succeeds; WeaviateQueryMode.supportsGroupBy() can include FETCH");
        Assertions.assertTrue(e.getMessage().contains("group is not present"),
            () -> "refused for an unexpected reason: " + e.getMessage());
    }

    /** The mode predicate has to agree with what the server just did. */
    @Test
    public void everyModeOfferedForGroupingIsAModeThatCanGroup() {
        Assertions.assertFalse(WeaviateQueryMode.FETCH.supportsGroupBy());
        for (WeaviateQueryMode mode : WeaviateQueryMode.values()) {
            if (mode != WeaviateQueryMode.FETCH) {
                Assertions.assertTrue(mode.supportsGroupBy(), () -> mode + " should group");
            }
        }
    }

    @Test
    public void groupingWorksOnBm25AndHybrid() {
        requireFixture();
        GroupBy groupBy = GroupBy.property("category", 10, 10);

        // Membership rather than order: all three reds score alike on this query, and a tie has
        // no promised ordering.
        Map<String, List<String>> bm25 =
            titlesByGroup(grouped(q -> q.bm25("red", b -> b.limit(50), groupBy)));
        Assertions.assertEquals(Set.of("red-near", "red-mid", "red-far"),
            new HashSet<>(bm25.get("red")));

        Map<String, List<String>> hybrid =
            titlesByGroup(grouped(q -> q.hybrid("red", b -> b.limit(50), groupBy)));
        Assertions.assertEquals(Set.of("red-near", "red-mid", "red-far"),
            new HashSet<>(hybrid.get("red")),
            "hybrid groups on a collection with no vectorizer -- if this starts failing with "
                + "VectorFromInput, the client stopped degrading to the keyword half");
    }

    /**
     * The composition the plugin cannot verify by inspection: rerank is a builder option and
     * GroupBy is a separate argument, but a grouped reply carries its objects under
     * group_by_results, so the score reader has to look there too.
     * <p>
     * Needs a reranker module on the collection. The fixture has none -- it is deliberately free
     * of model providers -- so this asserts the call shape compiles and dispatches, and the
     * score reading itself is covered by {@code WeaviateRerankSupportTest} against a built reply.
     */
    @Test
    public void groupedRerankDispatchesThroughTheSeam() {
        requireFixture();
        // Not asserting scores: without a reranker configured the server would reject the query.
        // What is asserted is that the grouped seam resolves at all -- if the client's internals
        // moved, this is the cheap early warning, and readData would silently drop the column.
        Assertions.assertTrue(WeaviateRerankSupport.isGroupedAvailable(),
            "the grouped rerank seam did not resolve; _rerankScore will be missing on grouped "
                + "searches even though the ordering is still applied");
    }

    /**
     * Grouping costs three of the four ranking metrics.
     * <p>
     * An object inside a group carries only {@code distance}, {@code id} and {@code vector}. That
     * is the server's entire group-hits type, not a gRPC quirk -- GraphQL introspection shows
     * {@code ...AdditionalGroupHitsAdditional} with those three fields where the ungrouped
     * {@code ...Additional} has thirteen, and asking for {@code score} there is a schema error.
     * Over gRPC the same request carries {@code score: true} and the reply comes back
     * {@code scorePresent=false}. The Python client encodes the limitation structurally: grouped
     * objects get a metadata type with no score field at all.
     * <p>
     * So the plugin hides those three columns while grouping rather than showing columns it can
     * never fill. If this test starts failing, the server gained per-object scores in groups and
     * the suppression in {@code readData} and {@code populateColumns} should come out.
     */
    @Test
    public void aGroupedSearchLosesEveryMetricExceptDistance() {
        requireFixture();
        GroupBy groupBy = GroupBy.property("category", 10, 10);

        QueryResponseGrouped<Map<String, Object>> keyword = grouped(q -> q.bm25("red",
            b -> b.limit(50).returnMetadata(Metadata.SCORE, Metadata.EXPLAIN_SCORE), groupBy));
        Assertions.assertFalse(keyword.objects().isEmpty(), "no rows to inspect");
        for (QueryObjectGrouped<Map<String, Object>> obj : keyword.objects()) {
            Assertions.assertNull(obj.metadata().score(),
                "a grouped BM25 now returns a score; stop hiding the _score column");
            Assertions.assertNull(obj.metadata().explainScore(),
                "a grouped BM25 now returns an explainScore; stop hiding that column");
        }

        // Distance is the exception, and the one metric the grid keeps while grouping.
        QueryResponseGrouped<Map<String, Object>> vector = grouped(q -> q.nearVector(PROBE,
            b -> b.limit(50).returnMetadata(Metadata.DISTANCE, Metadata.CERTAINTY), groupBy));
        for (QueryObjectGrouped<Map<String, Object>> obj : vector.objects()) {
            Assertions.assertNotNull(obj.metadata().distance(),
                "distance is the one metric a grouped search keeps");
            Assertions.assertNull(obj.metadata().certainty(),
                "a grouped search now returns certainty; stop hiding that column");
        }
    }

    /** Vectors are one of the three things a grouped object does keep, so the column stays. */
    @Test
    public void vectorsSurviveGrouping() {
        requireFixture();
        GroupBy groupBy = GroupBy.property("category", 10, 10);
        QueryResponseGrouped<Map<String, Object>> response = grouped(q -> q.nearVector(PROBE,
            b -> b.limit(50).includeVector(), groupBy));

        for (QueryObjectGrouped<Map<String, Object>> obj : response.objects()) {
            Assertions.assertNotNull(obj.vectors(), "grouped objects lost their vectors");
        }
    }

    /**
     * Distance is present on every grouped reply, but only means anything on a vector search.
     * <p>
     * A grouped BM25 gets {@code distancePresent=true} with a flat {@code 0.0}, and a grouped
     * hybrid gets {@code 0.0} for the rows its keyword half found and a real distance for the rows
     * its vector half found. A zero there reads as a perfect match when it actually means "never
     * vector-scored", so the plugin keeps {@code _distance} to the Near modes -- which is where
     * {@code WeaviateQueryMode.hasDistance()} already had it.
     * <p>
     * The consequence is worth stating plainly: a grouped BM25 or hybrid carries no usable ranking
     * metric at all, since the score is gone too. Only the row order survives.
     */
    @Test
    public void distanceIsOnlyMeaningfulOnAGroupedVectorSearch() {
        requireFixture();
        GroupBy groupBy = GroupBy.property("category", 10, 10);

        QueryResponseGrouped<Map<String, Object>> keyword = grouped(q -> q.bm25("red",
            b -> b.limit(50).returnMetadata(Metadata.DISTANCE), groupBy));
        for (QueryObjectGrouped<Map<String, Object>> obj : keyword.objects()) {
            Float d = obj.metadata().distance();
            Assertions.assertTrue(d == null || d == 0.0f,
                () -> "a grouped BM25 reported a real distance (" + d + "); if the server started "
                    + "computing one, WeaviateQueryMode.hasDistance() could include BM25");
        }

        QueryResponseGrouped<Map<String, Object>> vector = grouped(q -> q.nearVector(PROBE,
            b -> b.limit(50).returnMetadata(Metadata.DISTANCE), groupBy));
        boolean anyRealDistance = vector.objects().stream()
            .map(o -> o.metadata().distance())
            .anyMatch(d -> d != null && d > 0.0f);
        Assertions.assertTrue(anyRealDistance,
            "a grouped near-vector search should still report real distances");

        // The three Near modes are exactly the ones that offer the column.
        Assertions.assertFalse(WeaviateQueryMode.BM25.hasDistance());
        Assertions.assertFalse(WeaviateQueryMode.HYBRID.hasDistance());
        Assertions.assertTrue(WeaviateQueryMode.NEAR_VECTOR.hasDistance());
    }
}
