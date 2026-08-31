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

import org.jkiss.dbeaver.ext.weaviate.model.WeaviateRbacRest.PermissionInfo;
import org.jkiss.junit.DBeaverUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Grouping permissions into the rules the editor shows, and back again.
 */
public class WeaviateRoleRuleTest extends DBeaverUnitTest {

    private static PermissionInfo data(String action, String collection, String tenant) {
        return new PermissionInfo(action, "data",
            Map.of("collection", collection, "tenant", tenant, "object", "*"));
    }

    @Test
    public void actionsSharingAScopeBecomeOneRule() {
        List<WeaviateRoleRule> rules = WeaviateRoleRule.fromPermissions(List.of(
            data("read_data", "Orders", "*"),
            data("update_data", "Orders", "*")));
        Assertions.assertEquals(1, rules.size());
        Assertions.assertEquals(Set.of("read_data", "update_data"), rules.get(0).actions());
    }

    @Test
    public void differentScopesStayDifferentRules() {
        // The case a one-rule-per-kind editor silently merges. Merging would widen or narrow
        // somebody's access without saying so.
        List<WeaviateRoleRule> rules = WeaviateRoleRule.fromPermissions(List.of(
            data("read_data", "Orders", "*"),
            data("read_data", "Invoices", "*")));
        Assertions.assertEquals(2, rules.size());
    }

    @Test
    public void oneFieldDifferingIsEnoughToSplit() {
        List<WeaviateRoleRule> rules = WeaviateRoleRule.fromPermissions(List.of(
            data("read_data", "Orders", "acme"),
            data("read_data", "Orders", "globex")));
        Assertions.assertEquals(2, rules.size());
    }

    @Test
    public void scopelessActionsGroupTogether() {
        List<WeaviateRoleRule> rules = WeaviateRoleRule.fromPermissions(List.of(
            new PermissionInfo("read_cluster", null, Map.of())));
        Assertions.assertEquals(1, rules.size());
        Assertions.assertNull(rules.get(0).kind());
        Assertions.assertEquals("", rules.get(0).describeScope());
    }

    @Test
    public void theRoundTripPreservesEveryPermission() {
        List<PermissionInfo> original = List.of(
            data("read_data", "Orders", "*"),
            data("update_data", "Orders", "*"),
            data("read_data", "Invoices", "*"),
            new PermissionInfo("read_cluster", null, Map.of()),
            new PermissionInfo("manage_backups", "backups", Map.of("collection", "*")));
        List<PermissionInfo> round =
            WeaviateRoleRule.toPermissions(WeaviateRoleRule.fromPermissions(original));
        Assertions.assertEquals(
            original.stream().map(PermissionInfo::key).sorted().toList(),
            round.stream().map(PermissionInfo::key).sorted().toList());
    }

    @Test
    public void aPermissionThisBuildCannotModelSurvivesTheRoundTrip() {
        // The point of grouping on raw strings. An unknown kind still becomes a rule, and the
        // rule still turns back into exactly the permission it came from, so an edit that touches
        // something else leaves it alone rather than dropping it.
        PermissionInfo exotic =
            new PermissionInfo("read_hyperspace", "hyperspace", Map.of("lane", "7"));
        List<WeaviateRoleRule> rules = WeaviateRoleRule.fromPermissions(List.of(exotic));
        Assertions.assertEquals(1, rules.size());
        Assertions.assertFalse(rules.get(0).isEditable());
        Assertions.assertEquals(exotic.key(),
            WeaviateRoleRule.toPermissions(rules).get(0).key());
    }

    @Test
    public void legacyActionsAreShownButNotEditable() {
        // The server reads manage_data out of a 1.28-era policy and refuses it on a write, so
        // offering it in the editor would only produce a refusal.
        List<WeaviateRoleRule> rules = WeaviateRoleRule.fromPermissions(List.of(
            new PermissionInfo("manage_data", "data", Map.of("collection", "*"))));
        Assertions.assertFalse(rules.get(0).isEditable());
    }

    @Test
    public void aBlankRuleDefaultsEveryFieldToEverything() {
        WeaviateRoleRule rule = WeaviateRoleRule.blank("data");
        Assertions.assertEquals("*", rule.scope().get("collection"));
        Assertions.assertEquals("*", rule.scope().get("tenant"));
        Assertions.assertEquals("*", rule.scope().get("object"));
        Assertions.assertTrue(rule.actions().isEmpty());
    }

    @Test
    public void fixedValueFieldsStartAtTheServersDefault() {
        // A blank or "*" here would be a value the server rejects.
        Assertions.assertEquals("minimal", WeaviateRoleRule.blank("nodes").scope().get("verbosity"));
        Assertions.assertEquals("match", WeaviateRoleRule.blank("roles").scope().get("scope"));
        Assertions.assertEquals("oidc", WeaviateRoleRule.blank("groups").scope().get("groupType"));
    }

    @Test
    public void fixedValueFieldsOfferOnlyWhatTheServerAccepts() {
        Assertions.assertEquals(List.of("minimal", "verbose"),
            WeaviateRoleRule.choicesFor("verbosity"));
        Assertions.assertEquals(List.of("match", "all"), WeaviateRoleRule.choicesFor("scope"));
        Assertions.assertTrue(WeaviateRoleRule.choicesFor("collection").isEmpty());
    }

    @Test
    public void rulesDescribeThemselvesForTheTable() {
        WeaviateRoleRule rule = WeaviateRoleRule.fromPermissions(List.of(
            data("read_data", "Orders", "acme"),
            data("create_data", "Orders", "acme"))).get(0);
        Assertions.assertEquals("collection=Orders, object=*, tenant=acme", rule.describeScope());
        Assertions.assertTrue(rule.describeActions().contains("Read"));
        Assertions.assertTrue(rule.describeActions().contains("Create"));
        Assertions.assertEquals("data", rule.domain());
    }
}
