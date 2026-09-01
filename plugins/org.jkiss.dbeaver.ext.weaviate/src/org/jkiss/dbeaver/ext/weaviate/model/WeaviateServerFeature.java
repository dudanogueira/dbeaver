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

/**
 * Weaviate features that arrived in a particular server release, and the release they arrived in.
 * <p>
 * Add an entry whenever a Weaviate release introduces a field, section or option this plugin
 * offers -- the same instruction weaviate-studio's registry carries, and the reason this is one
 * readable list rather than version literals scattered through call sites.
 * <p>
 * Most of the versions here are <b>ported from weaviate-studio</b>
 * ({@code weaviate-add-collection/src/constants/versionFeatures.js}), so the two clients cannot
 * disagree about which release a feature landed in. Several are not used by this plugin yet; they
 * are here so the next feature to need one finds it rather than guessing.
 * <p>
 * <b>Tokenizers are the exception to trust.</b> {@link #TOKENIZATION_GSE},
 * {@link #TOKENIZATION_TRIGRAM} and the two Kagome entries carry studio's versions for parity, but
 * a version check is the wrong test for them: those tokenizers are server <em>build flags</em>
 * ({@code ENABLE_TOKENIZER_*}), not release features. A 1.39.0 server built without them still
 * answers {@code 422 unsupported tokenization strategy} -- verified -- so anything that actually
 * needs to know should ask the server rather than consult this enum.
 *
 * @see WeaviateVersions for what happens when the server version is unknown (short answer:
 *      the feature is treated as available)
 */
public enum WeaviateServerFeature {

    // -- Multi-tenancy
    MULTI_TENANCY(1, 20, 0),
    AUTO_TENANT_CREATION(1, 25, 2),
    AUTO_TENANT_ACTIVATION(1, 25, 2),

    // -- Replication
    REPLICATION_DELETION_STRATEGY(1, 28, 0),
    REPLICATION_ASYNC_ENABLED(1, 29, 0),
    /**
     * Moving a shard replica between nodes.
     * <p>
     * Gated at 1.32 rather than 1.31, when the endpoints first appeared, because 1.32 renamed the
     * whole wire model -- shardId to shard, sourceNodeId to sourceNode, targetNodeId to
     * targetNode, transferType to type -- and added the uncancelable, scheduledForCancel and
     * scheduledForDelete flags this plugin decides what to offer from. Against 1.31 the responses
     * would parse into blanks.
     */
    REPLICA_MOVEMENT(1, 32, 0),
    /** The INTEGRATING state, between FINALIZING and READY. */
    REPLICATION_INTEGRATING_STATE(1, 38, 0),

    // -- Vector index types and compression
    DYNAMIC_INDEX_TYPE(1, 25, 0),
    /** Cluster-based index with built-in RQ compression; preview. */
    HFRESH_INDEX_TYPE(1, 36, 0),
    RQ_QUANTIZATION_HNSW(1, 35, 0),
    RQ_QUANTIZATION_FLAT(1, 35, 0),

    // -- Property indexing
    INDEX_RANGE_FILTERS(1, 24, 0),

    // -- Object TTL
    OBJECT_TTL(1, 35, 0),

    // -- Tokenization methods. See the class note: these are build flags, not release features.
    TOKENIZATION_GSE(1, 24, 0),
    TOKENIZATION_TRIGRAM(1, 24, 0),
    TOKENIZATION_KAGOME_KR(1, 25, 7),
    TOKENIZATION_KAGOME_JA(1, 28, 0),

    // -- Multi-target vector search. From weaviate-studio's other, ad-hoc gate
    // (data-explorer/webview/utils/versionCheck.ts) rather than from its registry.
    MULTI_TARGET_NEAR(1, 26, 0),
    MULTI_TARGET_HYBRID(1, 27, 0),

    /**
     * {@code POST /v1/tokenize} and {@code /v1/schema/{c}/properties/{p}/tokenize}.
     * <p>
     * Not in weaviate-studio's registry -- it never calls the endpoint. Read out of the Weaviate
     * server source: {@code feat: add tokenizer endpoint and middleware integration (#10863)},
     * first released in v1.37.0.
     */
    TOKENIZE(1, 37, 0);

    private final int minMajor;
    private final int minMinor;
    private final int minPatch;

    WeaviateServerFeature(int minMajor, int minMinor, int minPatch) {
        this.minMajor = minMajor;
        this.minMinor = minMinor;
        this.minPatch = minPatch;
    }

    public int getMinMajor() {
        return minMajor;
    }

    public int getMinMinor() {
        return minMinor;
    }

    public int getMinPatch() {
        return minPatch;
    }

    /** The minimum as {@code 1.37.0}, for the "Requires Weaviate" message. */
    @NotNull
    public String getMinVersion() {
        return WeaviateVersions.format(minMajor, minMinor, minPatch);
    }

    /**
     * Whether a server reporting {@code serverVersion} has this feature. An unknown version
     * answers true -- see {@link WeaviateVersions}.
     */
    public boolean isSupportedBy(@Nullable String serverVersion) {
        return WeaviateVersions.isAtLeast(serverVersion, minMajor, minMinor, minPatch);
    }
}
