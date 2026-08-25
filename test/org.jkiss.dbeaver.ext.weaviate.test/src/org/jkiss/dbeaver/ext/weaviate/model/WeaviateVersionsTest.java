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

import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Version comparison for feature gating. Most of these cases exist because a real client got them
 * wrong -- see the notes on individual tests.
 */
public class WeaviateVersionsTest extends DBeaverUnitTest {

    @Test
    public void plainVersionsCompare() {
        Assertions.assertTrue(WeaviateVersions.isAtLeast("1.39.0", 1, 37, 0));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("1.37.0", 1, 37, 0), "equal is at least");
        Assertions.assertFalse(WeaviateVersions.isAtLeast("1.36.0", 1, 37, 0));
        Assertions.assertFalse(WeaviateVersions.isAtLeast("0.99.0", 1, 0, 0));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("2.0.0", 1, 39, 0), "major wins");
    }

    @Test
    public void patchIsCompared() {
        // Kagome KR's minimum is 1.25.7, so the patch digit is load-bearing in the real registry.
        Assertions.assertFalse(WeaviateVersions.isAtLeast("1.25.6", 1, 25, 7));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("1.25.7", 1, 25, 7));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("1.26.0", 1, 25, 7));
    }

    /**
     * A release candidate counts as its release. This is the case OSGi's parser cannot express --
     * it rejects the hyphen and answers 0.0.0 -- and it is the reason this class exists rather
     * than reusing {@code WeaviateDataSourceInfo#getDatabaseVersion()}.
     */
    @Test
    public void preReleaseCountsAsItsRelease() {
        Assertions.assertTrue(WeaviateVersions.isAtLeast("1.37.0-rc.1", 1, 37, 0));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("1.37.0-rc.0", 1, 37, 0));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("1.37.0-alpha", 1, 37, 0));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("1.37.0+build.5", 1, 37, 0));
        // ...and is still measured against the right release.
        Assertions.assertFalse(WeaviateVersions.isAtLeast("1.36.0-rc.1", 1, 37, 0));
    }

    /**
     * weaviate-studio's registry parser splits on "." and does not strip a leading v, so
     * "v1.35.0" becomes [0,35,0] and disables everything. Porting it verbatim would have imported
     * that.
     */
    @Test
    public void leadingVIsStripped() {
        Assertions.assertArrayEquals(new int[]{1, 35, 0}, WeaviateVersions.parse("v1.35.0"));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("v1.39.0", 1, 37, 0));
        Assertions.assertFalse(WeaviateVersions.isAtLeast("v1.30.0", 1, 37, 0));
    }

    /**
     * The literal "unknown" is what {@code WeaviateDataSourceInfo#getDatabaseProductVersion()}
     * returns before the server has been reached, and it is exactly the string weaviate-studio
     * sends to its own gate -- where, being truthy, it slips past the intended fail-open path,
     * parses to 0.0.0, and disables every gated control. Checked by name so that cannot happen
     * here.
     */
    @Test
    public void theLiteralUnknownIsTreatedAsNoVersion() {
        Assertions.assertNull(WeaviateVersions.parse("unknown"));
        Assertions.assertNull(WeaviateVersions.parse("UNKNOWN"));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("unknown", 1, 37, 0),
            "an unknown version must not hide a feature");
    }

    /**
     * The direction this fails in matters. weaviate-studio's Data Explorer assumes "not
     * compatible" when parsing fails, and shipped a release where a broken version lookup made it
     * warn "requires v1.26+" on v1.37+ servers. Guessing available costs a legible 404 from the
     * server instead.
     */
    @Test
    public void anUndeterminableVersionMeansAvailable() {
        Assertions.assertTrue(WeaviateVersions.isAtLeast(null, 1, 37, 0));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("", 1, 37, 0));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("   ", 1, 37, 0));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("not-a-version", 1, 37, 0));
        Assertions.assertTrue(WeaviateVersions.isAtLeast("1.37", 1, 37, 0),
            "a two-part version is not recognisable, so it must not hide the feature either");
    }

    /**
     * Null rather than a zeroed triple, so a caller cannot mistake "no idea" for "very old" --
     * which is the mistake this whole class is built to prevent.
     */
    @Test
    public void parseReturnsNullRatherThanZeroes() {
        Assertions.assertNull(WeaviateVersions.parse(null));
        Assertions.assertNull(WeaviateVersions.parse("garbage"));
        Assertions.assertArrayEquals(new int[]{0, 0, 0}, WeaviateVersions.parse("0.0.0"),
            "a genuine 0.0.0 is still a parsed version");
    }

    @Test
    public void surroundingWhitespaceIsIgnored() {
        Assertions.assertArrayEquals(new int[]{1, 39, 0}, WeaviateVersions.parse("  1.39.0 "));
    }

    @Test
    public void formatMatchesTheMessageStyle() {
        // Studio writes "Requires Weaviate >= 1.37.0" with a full three-part version.
        Assertions.assertEquals("1.37.0", WeaviateVersions.format(1, 37, 0));
        Assertions.assertEquals("1.25.7", WeaviateVersions.format(1, 25, 7));
    }
}
