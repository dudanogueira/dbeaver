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
import org.jkiss.code.Nullable;

import java.util.Arrays;

/**
 * One target of a multi-target vector search: which named vector to search, how heavily to weigh
 * it, and -- for Near Vector -- the query vector to search it with.
 * <p>
 * Plain JDK types only: this crosses into the UI bundle, whose classloader cannot see the shaded
 * client classes. {@code WeaviateCollection} translates it to a {@code Target} record at dispatch.
 * <p>
 * The two vector fields are mutually exclusive. Which one is populated follows the target's own
 * index: a multi-vector (ColBERT-style) index stores a matrix per object and must be queried with
 * a matrix, everything else with a flat vector. Near Text and Hybrid derive their vectors
 * server-side and leave both null.
 */
public final class WeaviateVectorTarget {

    private final String name;
    private final Float weight;
    private final float[] vector;
    private final float[][] multiVector;

    private WeaviateVectorTarget(
        @NotNull String name,
        @Nullable Float weight,
        @Nullable float[] vector,
        @Nullable float[][] multiVector
    ) {
        if (name.isBlank()) {
            throw new IllegalArgumentException("A target vector must be named");
        }
        if (vector != null && multiVector != null) {
            throw new IllegalArgumentException(
                "Target " + name + " has both a flat and a multi-vector query vector");
        }
        this.name = name;
        this.weight = weight;
        this.vector = vector == null ? null : vector.clone();
        this.multiVector = copy(multiVector);
    }

    /** A target with no query vector of its own -- Near Text and Hybrid. */
    @NotNull
    public static WeaviateVectorTarget of(@NotNull String name, @Nullable Float weight) {
        return new WeaviateVectorTarget(name, weight, null, null);
    }

    /** A target searched with a flat query vector. */
    @NotNull
    public static WeaviateVectorTarget of(@NotNull String name, @Nullable Float weight, @NotNull float[] vector) {
        return new WeaviateVectorTarget(name, weight, vector, null);
    }

    /** A target searched with a multi-vector (ColBERT-style) query matrix. */
    @NotNull
    public static WeaviateVectorTarget of(@NotNull String name, @Nullable Float weight, @NotNull float[][] multiVector) {
        return new WeaviateVectorTarget(name, weight, null, multiVector);
    }

    @NotNull
    public String getName() {
        return name;
    }

    @Nullable
    public Float getWeight() {
        return weight;
    }

    @Nullable
    public float[] getVector() {
        return vector == null ? null : vector.clone();
    }

    @Nullable
    public float[][] getMultiVector() {
        return copy(multiVector);
    }

    /** Whether this target carries a query matrix rather than a flat query vector. */
    public boolean isMulti() {
        return multiVector != null;
    }

    /** Whether this target carries a query vector at all; false for Near Text and Hybrid. */
    public boolean hasQueryVector() {
        return vector != null || multiVector != null;
    }

    /**
     * The query vector as the client's {@code Target.VectorTarget} wants it -- a {@code float[]}
     * or a {@code float[][]} behind an {@code Object}, which is how that record types it.
     */
    @Nullable
    Object queryVectorForClient() {
        return multiVector != null ? multiVector : vector;
    }

    @Nullable
    private static float[][] copy(@Nullable float[][] source) {
        if (source == null) {
            return null;
        }
        float[][] out = new float[source.length][];
        for (int i = 0; i < source.length; i++) {
            out[i] = source[i].clone();
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof WeaviateVectorTarget other)) {
            return false;
        }
        return name.equals(other.name)
            && java.util.Objects.equals(weight, other.weight)
            && Arrays.equals(vector, other.vector)
            && Arrays.deepEquals(multiVector, other.multiVector);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name, weight, Arrays.hashCode(vector), Arrays.deepHashCode(multiVector));
    }

    @Override
    public String toString() {
        return weight == null ? name : name + ":" + weight;
    }
}
