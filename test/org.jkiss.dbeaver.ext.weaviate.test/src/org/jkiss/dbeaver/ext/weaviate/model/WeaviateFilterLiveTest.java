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
import io.weaviate.client6.v1.api.collections.query.Filter;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs every filter operator against a real Weaviate and asserts the rows that come back.
 * <p>
 * The other filter tests assert what the translator builds. This one asserts what the server
 * does with it, which is the only way to catch a filter that is well-formed and still wrong --
 * a text operand against an int array, say, which builds cleanly and matches nothing.
 * <p>
 * Skipped unless {@code WEAVIATE_FILTER_FIXTURE_URL} is set, so the reactor stays hermetic. Seed
 * the data first with {@code testdata/seed_filter_fixture.py}, then:
 * <pre>
 * WEAVIATE_FILTER_FIXTURE_URL=http://localhost:8080 mvn verify ...
 * </pre>
 */
// Ordered by name so the coverage check below genuinely runs last; JUnit's default order is
// deterministic but not alphabetical, so it would otherwise see an empty set.
@TestMethodOrder(MethodOrderer.MethodName.class)
public class WeaviateFilterLiveTest extends DBeaverUnitTest {

    private static final String COLLECTION = "DBeaverFilterFixture";
    private static final String UUID_1 = "00000000-0000-4000-8000-000000000001";

    /** Operators this class has asserted, checked against the enum at the end. */
    private static final Set<WeaviateFilterOperator> EXERCISED =
        EnumSet.noneOf(WeaviateFilterOperator.class);

    /**
     * Skip unless a fixture server is configured. Called first in every test rather than from
     * the query helper: an abort raised deeper down would be caught by the helper's own error
     * handling and reported as a failure instead of a skip.
     */
    private static void requireFixture() {
        String url = System.getenv("WEAVIATE_FILTER_FIXTURE_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(),
            "set WEAVIATE_FILTER_FIXTURE_URL to run the live filter tests");
    }

    private static WeaviateClient connect() {
        String url = System.getenv("WEAVIATE_FILTER_FIXTURE_URL");
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

    /**
     * Run one filter row through the real translator and return the names it matched, sorted so
     * the assertion does not depend on result order.
     */
    private static List<String> namesMatching(
        String property,
        WeaviateFilterOperator op,
        String value,
        DBPDataKind kind
    ) {
        EXERCISED.add(op);
        WeaviateFilterRow row = new WeaviateFilterRow(property, op, value, kind);
        Filter filter = WeaviateFilterTranslator.translateRows(List.of(row), false);
        Assertions.assertNotNull(filter, () -> op + " on " + property + " produced no filter");
        return namesFor(filter);
    }

    private static List<String> namesFor(Filter filter) {
        try (WeaviateClient client = connect()) {
            var response = client.collections.use(COLLECTION).query
                .fetchObjects(b -> b.limit(50).filters(filter));
            List<String> names = new ArrayList<>();
            for (var o : response.objects()) {
                Object name = o.properties().get("name");
                names.add(name == null ? "(null)" : name.toString());
            }
            names.sort(String::compareTo);
            return names;
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            throw new IllegalStateException("live filter query failed: " + cause.getMessage(), e);
        }
    }

    private static void assertNames(List<String> actual, String... expected) {
        List<String> want = new ArrayList<>(List.of(expected));
        want.sort(String::compareTo);
        Assertions.assertEquals(want, actual);
    }

    @Test
    public void textComparisons() {
        requireFixture();
        assertNames(namesMatching("name", WeaviateFilterOperator.EQUALS, "alpha", DBPDataKind.STRING),
            "alpha");
        // The null row matches too: Weaviate reads "not equal to alpha" as including the objects
        // that have no name at all, which is worth pinning down since SQL would not.
        assertNames(namesMatching("name", WeaviateFilterOperator.NOT_EQUALS, "alpha", DBPDataKind.STRING),
            "(null)", "beta", "gamma");
    }

    @Test
    public void likeWildcards() {
        requireFixture();
        assertNames(namesMatching("name", WeaviateFilterOperator.LIKE, "*a", DBPDataKind.STRING),
            "alpha", "beta", "gamma");
        assertNames(namesMatching("name", WeaviateFilterOperator.LIKE, "?eta", DBPDataKind.STRING),
            "beta");
        assertNames(namesMatching("name", WeaviateFilterOperator.NOT_LIKE, "*a", DBPDataKind.STRING),
            "(null)");
    }

    @Test
    public void nullTests() {
        requireFixture();
        assertNames(namesMatching("name", WeaviateFilterOperator.IS_NULL, null, DBPDataKind.STRING),
            "(null)");
        assertNames(namesMatching("name", WeaviateFilterOperator.IS_NOT_NULL, null, DBPDataKind.STRING),
            "alpha", "beta", "gamma");
    }

    @Test
    public void numericComparisons() {
        requireFixture();
        assertNames(namesMatching("rank", WeaviateFilterOperator.GREATER, "1", DBPDataKind.NUMERIC),
            "beta", "gamma");
        assertNames(namesMatching("rank", WeaviateFilterOperator.GREATER_EQUALS, "2", DBPDataKind.NUMERIC),
            "beta", "gamma");
        assertNames(namesMatching("rank", WeaviateFilterOperator.LESS, "2", DBPDataKind.NUMERIC),
            "alpha");
        assertNames(namesMatching("rank", WeaviateFilterOperator.LESS_EQUALS, "1", DBPDataKind.NUMERIC),
            "alpha");
        assertNames(namesMatching("score", WeaviateFilterOperator.LESS, "2.5", DBPDataKind.NUMERIC),
            "alpha");
    }

    @Test
    public void between() {
        requireFixture();
        assertNames(namesMatching("rank", WeaviateFilterOperator.BETWEEN, "1,2", DBPDataKind.NUMERIC),
            "alpha", "beta");
    }

    @Test
    public void booleanEquality() {
        requireFixture();
        assertNames(namesMatching("flag", WeaviateFilterOperator.EQUALS, "true", DBPDataKind.BOOLEAN),
            "alpha", "gamma");
    }

    @Test
    public void dateComparison() {
        requireFixture();
        assertNames(namesMatching("when", WeaviateFilterOperator.GREATER,
            "2024-03-01T00:00:00Z", DBPDataKind.DATETIME), "beta", "gamma");
    }

    @Test
    public void containsOnTextArray() {
        requireFixture();
        assertNames(namesMatching("tags", WeaviateFilterOperator.CONTAINS_ANY, "x", DBPDataKind.STRING),
            "alpha");
        assertNames(namesMatching("tags", WeaviateFilterOperator.CONTAINS_ANY, "y,z", DBPDataKind.STRING),
            "alpha", "beta", "gamma");
        assertNames(namesMatching("tags", WeaviateFilterOperator.CONTAINS_ALL, "y,z", DBPDataKind.STRING),
            "beta");
        assertNames(namesMatching("tags", WeaviateFilterOperator.CONTAINS_NONE, "x", DBPDataKind.STRING),
            "(null)", "beta", "gamma");
    }

    /**
     * The int-array case is the one that matters most: the row used to coerce every list element
     * to String, so this filter went out with text operands and matched nothing.
     */
    @Test
    public void containsOnIntArray() {
        requireFixture();
        assertNames(namesMatching("nums", WeaviateFilterOperator.CONTAINS_ANY, "1", DBPDataKind.NUMERIC),
            "alpha");
        assertNames(namesMatching("nums", WeaviateFilterOperator.CONTAINS_ALL, "2,3", DBPDataKind.NUMERIC),
            "beta");
        assertNames(namesMatching("nums", WeaviateFilterOperator.CONTAINS_NONE, "1,2", DBPDataKind.NUMERIC),
            "(null)", "gamma");
    }

    @Test
    public void uuidFilter() {
        requireFixture();
        assertNames(namesMatching(WeaviateFilterTranslator.UUID_COLUMN,
            WeaviateFilterOperator.EQUALS, UUID_1, DBPDataKind.STRING), "alpha");
    }

    /**
     * The timestamp columns are metadata, not properties. Filtering them as a property used to
     * target a path with nothing behind it, which returns everything or nothing rather than
     * failing -- so the assertion here is that it actually narrows.
     */
    @Test
    public void creationTimeFilterNarrows() {
        requireFixture();
        List<String> future = namesMatching(WeaviateColumns.CREATED,
            WeaviateFilterOperator.GREATER, "2999-01-01T00:00:00Z", DBPDataKind.DATETIME);
        Assertions.assertTrue(future.isEmpty(), () -> "nothing was created after 2999: " + future);

        // 2020, not 2000: the server compares these timestamps as strings, so a bound whose
        // epoch-millis has fewer digits than the data's sorts wrongly. Anything from 2001 on has
        // 13 digits and behaves. Verified against 1.39.
        List<String> past = namesMatching(WeaviateColumns.CREATED,
            WeaviateFilterOperator.GREATER, "2020-01-01T00:00:00Z", DBPDataKind.DATETIME);
        Assertions.assertEquals(4, past.size(), () -> "everything was created after 2020: " + past);
    }

    @Test
    public void rowsCombineWithAndOr() {
        requireFixture();
        WeaviateFilterRow rank = new WeaviateFilterRow(
            "rank", WeaviateFilterOperator.GREATER, "1", DBPDataKind.NUMERIC);
        WeaviateFilterRow tag = new WeaviateFilterRow(
            "tags", WeaviateFilterOperator.CONTAINS_ANY, "z", DBPDataKind.STRING);

        assertNames(namesFor(WeaviateFilterTranslator.translateRows(List.of(rank, tag), false)),
            "beta", "gamma");
        assertNames(namesFor(WeaviateFilterTranslator.translateRows(List.of(rank, tag), true)),
            "beta", "gamma");

        WeaviateFilterRow alpha = new WeaviateFilterRow(
            "name", WeaviateFilterOperator.EQUALS, "alpha", DBPDataKind.STRING);
        // AND of two disjoint rows matches nothing; OR of the same two matches both.
        assertNames(namesFor(WeaviateFilterTranslator.translateRows(List.of(alpha, tag), false)));
        assertNames(namesFor(WeaviateFilterTranslator.translateRows(List.of(alpha, tag), true)),
            "alpha", "beta", "gamma");
    }

    /**
     * Runs last by name, and fails if an operator was added to the enum without a live case --
     * an operator nobody has watched against a real server is the kind that ships broken.
     */
    @Test
    public void zzEveryOperatorHasALiveCase() {
        requireFixture();
        Set<WeaviateFilterOperator> missing =
            new LinkedHashSet<>(List.of(WeaviateFilterOperator.values()));
        missing.removeAll(EXERCISED);
        Assertions.assertTrue(missing.isEmpty(),
            () -> "no live assertion covers: " + missing);
    }
}
