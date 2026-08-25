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

import java.util.Objects;

/**
 * A group-by request: bucket the matches by one property, capping both how many groups come back
 * and how many objects each group carries.
 * <p>
 * Plain JDK types only -- this crosses into the UI bundle. Translated to the client's
 * {@code GroupBy} record at dispatch, inside {@code WeaviateCollection}.
 * <p>
 * Not to be confused with a generative <em>grouped task</em>, which is one prompt run over the
 * whole result set and has nothing to do with bucketing.
 */
public final class WeaviateGroupBySpec {

    /**
     * What the server falls back to. The client's {@code GroupBy} takes primitive ints with no
     * "unset" value, so something concrete has to be sent either way; these are the numbers the
     * panel starts from.
     */
    public static final int DEFAULT_MAX_GROUPS = 10;
    public static final int DEFAULT_MAX_OBJECTS_PER_GROUP = 10;

    private final String property;
    private final int maxGroups;
    private final int maxObjectsPerGroup;
    private final boolean withGroupStats;

    public WeaviateGroupBySpec(
        @NotNull String property,
        int maxGroups,
        int maxObjectsPerGroup,
        boolean withGroupStats
    ) {
        if (property.isBlank()) {
            throw new IllegalArgumentException("Group by needs a property to group on");
        }
        // Rejected rather than clamped: a zero here comes back as an empty grid with no
        // explanation, which reads as a broken query rather than as a query asking for nothing.
        if (maxGroups < 1) {
            throw new IllegalArgumentException("Number of groups must be at least 1");
        }
        if (maxObjectsPerGroup < 1) {
            throw new IllegalArgumentException("Objects per group must be at least 1");
        }
        this.property = property;
        this.maxGroups = maxGroups;
        this.maxObjectsPerGroup = maxObjectsPerGroup;
        this.withGroupStats = withGroupStats;
    }

    @NotNull
    public String getProperty() {
        return property;
    }

    public int getMaxGroups() {
        return maxGroups;
    }

    public int getMaxObjectsPerGroup() {
        return maxObjectsPerGroup;
    }

    /**
     * Whether to show the per-group counts and distances alongside the group name. Opt-in: they
     * repeat identically on every row of a group, so they are noise unless asked for.
     */
    public boolean isWithGroupStats() {
        return withGroupStats;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WeaviateGroupBySpec other
            && property.equals(other.property)
            && maxGroups == other.maxGroups
            && maxObjectsPerGroup == other.maxObjectsPerGroup
            && withGroupStats == other.withGroupStats;
    }

    @Override
    public int hashCode() {
        return Objects.hash(property, maxGroups, maxObjectsPerGroup, withGroupStats);
    }

    @Override
    public String toString() {
        return property + "/" + maxGroups + "x" + maxObjectsPerGroup;
    }
}
