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

import io.weaviate.client6.v1.api.collections.query.Boost;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * A nudge applied to the ranking after the search has scored it.
 * <p>
 * Every mode takes one: the client puts {@code boost} on {@code BaseQueryOptions}, not on any
 * single operator, so a plain fetch can be boosted as readily as a hybrid search.
 * <p>
 * <b>{@code weight} is a blend fraction, not a multiplier.</b> The server takes it in
 * {@code [0, 1]} and refuses anything else outright -- <em>boost: weight must be between 0 and 1,
 * got 5.000000</em>. Measured against the seeded fixture, 0 leaves the ranking exactly as it was
 * and 1 lets the boost dominate it; the interesting settings are in between. Omitting it is not
 * the same as sending 0, since the server then picks its own.
 * <p>
 * One record with a kind tag rather than a type per kind: the fields form a tagged union, the UI
 * binds one row of controls and shows the ones the kind reads, and every combination that reaches
 * the server is checked here instead of at three separate call sites.
 *
 * @param kind     which way the boost rewards a document
 * @param property the property it reads. For {@link WeaviateBoostKind#TIME_DECAY} this must be a
 *                 {@code date}; the server names the offending property if it is not
 * @param origin   the value documents are rewarded for sitting near. Required by
 *                 {@link WeaviateBoostKind#NUMERIC_DECAY}; optional for time decay, where leaving
 *                 it out means now
 * @param scale    how far from the origin the reward has faded. Required by both decay kinds --
 *                 a number, or a duration such as {@code 30d} for time decay
 * @param offset   a band around the origin that is not penalised at all, or null
 * @param curve    the falloff shape, or null for the server's own
 * @param decay    how much reward is left at one scale away, or null for the server's own
 * @param modifier flattens the raw value; read by {@link WeaviateBoostKind#PROPERTY_VALUE} alone
 * @param weight   how far the boost may move the ranking, in {@code [0, 1]}
 * @param depth    how many of the top candidates are re-scored, or null for the server's own
 */
public record WeaviateBoostSpec(
    @NotNull WeaviateBoostKind kind,
    @NotNull String property,
    @Nullable String origin,
    @Nullable String scale,
    @Nullable String offset,
    @Nullable WeaviateBoostCurve curve,
    @Nullable Float decay,
    @Nullable WeaviateBoostModifier modifier,
    @Nullable Float weight,
    @Nullable Integer depth
) {

    public WeaviateBoostSpec {
        if (property == null || property.isBlank()) {
            throw new IllegalArgumentException("A boost needs a property to read");
        }
        if (weight != null && (weight < 0f || weight > 1f)) {
            // Refusing here rather than letting the server do it: "weight" reads as a multiplier,
            // so a value like 5 is the natural first guess and the server's rejection arrives
            // only after the query has been sent.
            throw new IllegalArgumentException(
                "Boost weight must be between 0 and 1, got " + weight);
        }
        if (kind.isDecay() && (scale == null || scale.isBlank())) {
            throw new IllegalArgumentException(kind.getLabel() + " needs a scale");
        }
        if (kind == WeaviateBoostKind.NUMERIC_DECAY) {
            requireNumber(origin, "origin");
            requireNumber(scale, "scale");
            if (offset != null && !offset.isBlank()) {
                requireNumber(offset, "offset");
            }
        }
    }

    private static void requireNumber(@Nullable String value, @NotNull String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A numeric decay needs a " + field);
        }
        try {
            Float.parseFloat(value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                "A numeric decay's " + field + " must be a number, got \"" + value + "\"");
        }
    }

    @NotNull
    public Boost toClientType() {
        return switch (kind) {
            case PROPERTY_VALUE -> Boost.numericProperty(property, b -> {
                if (modifier != null) b.modifier(modifier.toClientType());
                applyCommon(b);
                return b;
            });
            case NUMERIC_DECAY -> Boost.numericDecay(
                property, Float.parseFloat(origin.strip()), Float.parseFloat(scale.strip()), b -> {
                    if (offset != null && !offset.isBlank()) b.offset(Float.parseFloat(offset.strip()));
                    if (curve != null) b.curve(curve.toClientType());
                    if (decay != null) b.decay(decay);
                    applyCommon(b);
                    return b;
                });
            case TIME_DECAY -> Boost.timeDecay(property, scale.strip(), b -> {
                if (origin != null && !origin.isBlank()) b.origin(origin.strip());
                if (offset != null && !offset.isBlank()) b.offset(offset.strip());
                if (curve != null) b.curve(curve.toClientType());
                if (decay != null) b.decay(decay);
                applyCommon(b);
                return b;
            });
        };
    }

    /** Weight and depth sit on the shared builder base, so every kind takes them the same way. */
    private void applyCommon(@NotNull Boost.Builder<?> b) {
        if (weight != null) {
            b.weight(weight);
        }
        if (depth != null && depth > 0) {
            b.depth(depth);
        }
    }
}
