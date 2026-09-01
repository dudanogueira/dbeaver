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

import io.weaviate.client6.v1.api.collections.query.Filter;
import io.weaviate.client6.v1.api.collections.query.SortBy;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link WeaviateQuerySpec} as the one-line call that DBeaver shows as "the query".
 * <p>
 * Weaviate has no query language, so there is no statement text to log. This writes what the
 * equivalent client call would look like -- {@code hybrid("shoes", alpha=0.7, limit=200)} -- which
 * is what appears in the query log, in error messages, and in the execution statistics.
 * <p>
 * Kept apart from {@link WeaviateQueryRequest} deliberately: that class builds what is sent, this
 * one describes it. They take the same input and are edited at the same times, but a mistake here
 * is a cosmetic one and a mistake there changes what the server is asked for.
 */
final class WeaviateQueryDescription {

    private WeaviateQueryDescription() {
        // Utility class.
    }

    @NotNull
    static String describeQuery(
        @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter,
        @NotNull List<SortBy> sortBy,
        int limit,
        int offset
    ) {
        // Build the argument list first, then join it. The previous version patched up
        // trailing separators in place and emitted an unbalanced "fetchObjects)" whenever
        // a FETCH query had no arguments at all.
        String function;
        List<String> args = new ArrayList<>();
        switch (spec.getMode()) {
            case BM25:
                function = "bm25";
                args.add("query=" + quote(spec.getQuery()));
                break;
            case NEAR_TEXT:
                function = "nearText";
                args.add("query=" + quote(spec.getQuery()));
                break;
            case NEAR_VECTOR:
                function = "nearVector";
                if (!spec.hasTargets()) {
                    args.add("dim=" + (spec.getVector() == null ? 0 : spec.getVector().length));
                }
                break;
            case NEAR_OBJECT:
                function = "nearObject";
                args.add("id=" + quote(spec.getObjectId()));
                break;
            case HYBRID:
                function = "hybrid";
                args.add("query=" + quote(spec.getQuery()));
                if (spec.getAlpha() != null) args.add("alpha=" + spec.getAlpha());
                break;
            case FETCH:
            default:
                function = "fetchObjects";
                break;
        }
        describeTargets(spec, args);
        if (spec.getRerank() != null) {
            args.add("rerank=" + spec.getRerank());
        }
        if (spec.isGrouped()) {
            args.add("groupBy=" + spec.getGroupBy());
        }
        WeaviateGenerativeTask generative = spec.getGenerative();
        if (generative != null) {
            String kind = generative.getSinglePrompt() != null && generative.getGroupedTask() != null
                ? "single+grouped"
                : generative.getSinglePrompt() != null ? "single" : "grouped";
            args.add("generate=" + kind);
            if (generative.getProvider() != null) {
                args.add("provider=" + generative.getProvider().name());
            }
        }
        if (limit > 0) args.add("limit=" + limit);
        if (offset > 0) args.add("offset=" + offset);
        if (filter != null) args.add("filter=" + filter);
        if (!sortBy.isEmpty()) args.add("sort=" + sortBy);
        return function + "(" + String.join(", ", args) + ")";
    }

    /**
     * Add the target vectors and their join strategy to the statement shown in the result tab, so
     * what is on screen says which vectors were actually searched and how they were weighed.
     */
    static void describeTargets(@NotNull WeaviateQuerySpec spec, @NotNull List<String> args) {
        if (!spec.hasTargets()) {
            return;
        }
        WeaviateVectorCombination combination = spec.getCombination();
        boolean weighted = combination != null && combination.usesWeights();
        List<String> described = new ArrayList<>(spec.getTargets().size());
        for (WeaviateVectorTarget target : spec.getTargets()) {
            StringBuilder sb = new StringBuilder(target.getName());
            // The weight only shows where it does something -- see WeaviateVectorCombination.
            if (weighted && target.getWeight() != null) {
                sb.append(':').append(target.getWeight());
            }
            if (target.isMulti()) {
                float[][] multi = target.getMultiVector();
                sb.append("[").append(multi.length).append('x')
                    .append(multi.length == 0 ? 0 : multi[0].length).append(']');
            } else if (target.getVector() != null) {
                sb.append("[").append(target.getVector().length).append(']');
            }
            described.add(sb.toString());
        }
        args.add("targets=[" + String.join(", ", described) + "]");
        // One target is not joined with anything, so naming a strategy would be noise.
        if (spec.getTargets().size() > 1) {
            args.add("join=" + (combination == null ? WeaviateVectorCombination.MIN : combination).name());
        }
    }

    @NotNull
    static String quote(@Nullable String s) {
        return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
    }
}
