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

import java.util.List;

/**
 * The filter decides what a bulk activate or deactivate touches, so its edges are worth pinning
 * down: too wide and tenants are changed that the user did not mean to name.
 */
public class WeaviateTenantFilterTest extends DBeaverUnitTest {

    private static final List<WeaviateTenant> TENANTS = List.of(
        tenant("acme-eu-west"),
        tenant("acme-us-east"),
        tenant("acme-ap-south"),
        tenant("globex-eu-west"),
        tenant("globex-acme-1"),
        tenant("initech-ap-south"));

    private static WeaviateTenant tenant(String name) {
        return new WeaviateTenant(name, WeaviateTenantStatus.ACTIVE);
    }

    private static List<String> matched(String pattern) {
        return WeaviateTenantFilter.of(pattern).filter(TENANTS).stream()
            .map(WeaviateTenant::name).toList();
    }

    @Test
    public void blankMatchesEverything() {
        for (String blank : new String[]{null, "", "   "}) {
            WeaviateTenantFilter filter = WeaviateTenantFilter.of(blank);
            Assertions.assertTrue(filter.isMatchAll(), () -> "blank should not filter: [" + blank + "]");
            Assertions.assertEquals(TENANTS.size(), filter.filter(TENANTS).size());
        }
    }

    @Test
    public void plainTextIsASubstringMatch() {
        // The forgiving reading, for finding a tenant rather than defining a set.
        Assertions.assertEquals(
            List.of("acme-eu-west", "acme-us-east", "acme-ap-south", "globex-acme-1"),
            matched("acme"));
        Assertions.assertFalse(WeaviateTenantFilter.of("acme").isWildcard());
    }

    @Test
    public void aWildcardPatternIsAnchored() {
        // The whole point of the split: as a substring, "acme-*" would also drag in
        // globex-acme-1, which is not what someone writing a pattern before pressing
        // Deactivate means.
        Assertions.assertEquals(
            List.of("acme-eu-west", "acme-us-east", "acme-ap-south"),
            matched("acme-*"));
        Assertions.assertTrue(WeaviateTenantFilter.of("acme-*").isWildcard());
    }

    @Test
    public void theUsersOwnExampleSelectsOneGroup() {
        // company-tenanttype-* from the request: two segments pinned, the rest free.
        Assertions.assertEquals(List.of("acme-ap-south"), matched("acme-ap-*"));
    }

    @Test
    public void aWildcardCanSitInTheMiddleOrAtTheStart() {
        Assertions.assertEquals(List.of("acme-ap-south", "initech-ap-south"), matched("*-ap-south"));
        Assertions.assertEquals(List.of("acme-eu-west", "globex-eu-west"), matched("*eu-west"));
        Assertions.assertEquals(List.of("acme-eu-west"), matched("acme-*west"));
    }

    @Test
    public void questionMarkIsExactlyOneCharacter() {
        Assertions.assertEquals(List.of("globex-acme-1"), matched("globex-acme-?"));
        Assertions.assertEquals(List.of(), matched("globex-acme-??"));
    }

    @Test
    public void matchingIsCaseInsensitiveBothWays() {
        Assertions.assertEquals(3, matched("ACME-*").size());
        Assertions.assertEquals(4, matched("AcMe").size());
        Assertions.assertTrue(WeaviateTenantFilter.of("*-EU-WEST").matches("acme-eu-west"));
    }

    @Test
    public void starAloneIsTheSameAsNoFilter() {
        // Reported as match-all rather than as a glob, so the count label does not claim to be
        // excluding something.
        WeaviateTenantFilter filter = WeaviateTenantFilter.of("***");
        Assertions.assertTrue(filter.isMatchAll());
        Assertions.assertEquals(TENANTS.size(), filter.filter(TENANTS).size());
    }

    /**
     * Tenant names are arbitrary strings. A regex metacharacter in one must be matched as itself,
     * or a pattern would silently select the wrong tenants.
     */
    @Test
    public void regexMetacharactersAreLiteral() {
        List<WeaviateTenant> odd = List.of(
            tenant("a.c"), tenant("abc"), tenant("a+b"), tenant("a(b)c"), tenant("x[1]"));

        Assertions.assertEquals(List.of("a.c"),
            WeaviateTenantFilter.of("a.c").filter(odd).stream().map(WeaviateTenant::name).toList(),
            "a dot must not match any character");
        Assertions.assertEquals(List.of("a+b"),
            WeaviateTenantFilter.of("a+b*").filter(odd).stream().map(WeaviateTenant::name).toList());
        Assertions.assertEquals(List.of("x[1]"),
            WeaviateTenantFilter.of("x[*]").filter(odd).stream().map(WeaviateTenant::name).toList(),
            "an unclosed character class must not blow up the pattern");
    }

    /**
     * Non-ASCII names are why literal runs are quoted rather than escaped character by character:
     * Java rejects a backslash before an alphabetic character it does not recognise.
     */
    @Test
    public void nonAsciiNamesAreMatchedAndDoNotThrow() {
        List<WeaviateTenant> accented = List.of(tenant("são-paulo-1"), tenant("sao-paulo-2"));
        Assertions.assertEquals(List.of("são-paulo-1"),
            WeaviateTenantFilter.of("são-*").filter(accented).stream()
                .map(WeaviateTenant::name).toList());
        Assertions.assertTrue(WeaviateTenantFilter.of("SÃO-*").matches("são-paulo-1"),
            "case folding should apply to non-ASCII too");
    }

    @Test
    public void nothingMatchesNull() {
        Assertions.assertFalse(WeaviateTenantFilter.of("acme-*").matches(null));
        Assertions.assertFalse(WeaviateTenantFilter.of("").matches(null));
    }
}
