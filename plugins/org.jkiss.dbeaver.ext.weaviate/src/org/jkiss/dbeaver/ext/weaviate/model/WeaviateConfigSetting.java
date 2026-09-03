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

import java.util.List;

/**
 * The collection settings a running server will actually accept a change to.
 * <p>
 * Every entry here was measured rather than read off the documentation, by
 * {@code testdata/probe_collection_mutability.py}: one {@code PUT} per setting against a populated
 * throwaway collection, with the definition read back afterwards. That mattered more than expected
 * -- the docs were wrong in three places, in both directions, and two settings the docs never
 * mention are accepted and then silently ignored.
 * <p>
 * <b>What is deliberately absent is the point of the list.</b> Eighteen settings are refused by the
 * server and two more are accepted and dropped, and none of them appears here: an editor that
 * offered them would be offering a change that cannot happen. Notably missing, and each for a
 * measured reason:
 * <ul>
 * <li>{@code efConstruction}, {@code maxConnections}, {@code distance} -- they describe a graph
 *     that already exists;</li>
 * <li>the vector index's own {@code cleanupIntervalSeconds}, which the documentation lists as
 *     mutable and the server refuses, unlike the identically-named inverted-index one, which is
 *     here;</li>
 * <li>{@code indexTimestamps}, {@code indexNullState}, {@code indexPropertyLength} -- refused, and
 *     absent from the definition entirely while false, so they cannot even be shown truthfully;</li>
 * <li>{@code usingBlockMaxWAND} and {@code replicationConfig.asyncEnabled} -- accepted with a 200
 *     and then ignored, which is the worst thing to put a control in front of;</li>
 * <li>every {@code shardingConfig} field, {@code replicationConfig.factor}, the vectorizer, the
 *     index type, and every property attribute except its description.</li>
 * </ul>
 * Quantization and the module settings are changeable too, and are not here: each needs a control
 * of its own rather than a row in a form -- quantization because it cannot be undone, the modules
 * because there are five rerankers and fourteen generative providers to choose between.
 *
 * @see WeaviateConfigDocument for how a change is carried
 */
public enum WeaviateConfigSetting {

    DESCRIPTION(
        Group.GENERAL, Kind.TEXT, "description", "Description",
        "Free text stored with the collection. The only thing on a collection that renames nothing "
            + "and breaks nothing."),

    BM25_B(
        Group.INVERTED_INDEX, Kind.DECIMAL, "invertedIndexConfig.bm25.b", "BM25 b",
        "How much a long document is penalised for its length. 0 ignores length entirely, 1 "
            + "normalises fully. The server's default is 0.75.",
        0d, 1d),
    BM25_K1(
        Group.INVERTED_INDEX, Kind.DECIMAL, "invertedIndexConfig.bm25.k1", "BM25 k1",
        "How quickly repeating a term stops helping. Low values saturate sooner. The server's "
            + "default is 1.2.",
        0d, 10d),
    INVERTED_CLEANUP(
        Group.INVERTED_INDEX, Kind.INTEGER, "invertedIndexConfig.cleanupIntervalSeconds",
        "Cleanup interval (s)",
        "How often the inverted index tidies up deleted entries. Mutable here, unlike the "
            + "identically-named setting on the vector index, which the server refuses.",
        1d, null),
    STOPWORD_PRESET(
        Group.INVERTED_INDEX, Kind.CHOICE, "invertedIndexConfig.stopwords.preset",
        "Stopword preset",
        "The base list of words the keyword index ignores.",
        List.of("en", "none")),
    STOPWORD_ADDITIONS(
        Group.INVERTED_INDEX, Kind.WORD_LIST, "invertedIndexConfig.stopwords.additions",
        "Also ignore",
        "Words to add to the preset, comma separated."),
    STOPWORD_REMOVALS(
        Group.INVERTED_INDEX, Kind.WORD_LIST, "invertedIndexConfig.stopwords.removals",
        "Do not ignore",
        "Words to take back out of the preset, comma separated."),

    DELETION_STRATEGY(
        Group.REPLICATION, Kind.CHOICE, "replicationConfig.deletionStrategy",
        "Deletion strategy",
        "How a delete that conflicts across replicas is resolved. The replication factor is not "
            + "here: the schema API refuses it, and a replica is added or moved through the "
            + "Replication branch instead.",
        List.of("NoAutomatedResolution", "DeleteOnConflict", "TimeBasedResolution")),

    EF(
        Group.VECTOR_INDEX, Kind.INTEGER, "ef", "ef",
        "How wide the search beam is at query time. Higher finds more and costs more. -1 hands it "
            + "to the dynamic settings below.",
        -1d, null),
    DYNAMIC_EF_MIN(
        Group.VECTOR_INDEX, Kind.INTEGER, "dynamicEfMin", "Dynamic ef min",
        "The floor when ef is -1.", 1d, null),
    DYNAMIC_EF_MAX(
        Group.VECTOR_INDEX, Kind.INTEGER, "dynamicEfMax", "Dynamic ef max",
        "The ceiling when ef is -1.", 1d, null),
    DYNAMIC_EF_FACTOR(
        Group.VECTOR_INDEX, Kind.INTEGER, "dynamicEfFactor", "Dynamic ef factor",
        "Multiplied by the query limit to pick an ef between the floor and the ceiling.",
        1d, null),
    FLAT_SEARCH_CUTOFF(
        Group.VECTOR_INDEX, Kind.INTEGER, "flatSearchCutoff", "Flat search cutoff",
        "Below this many candidates after filtering, the index is scanned rather than walked.",
        0d, null),
    VECTOR_CACHE_MAX(
        Group.VECTOR_INDEX, Kind.LONG, "vectorCacheMaxObjects", "Vector cache max objects",
        "How many vectors may be held in memory. The default is effectively unlimited.",
        0d, null),
    FILTER_STRATEGY(
        Group.VECTOR_INDEX, Kind.CHOICE, "filterStrategy", "Filter strategy",
        "How a filtered vector search is run. ACORN walks the graph honouring the filter; "
            + "sweeping searches first and filters after.",
        List.of("acorn", "sweeping")),
    SKIP(
        Group.VECTOR_INDEX, Kind.BOOLEAN, "skip", "Skip vectorization",
        "Stop building vectors for this index. Documented immutable; the server accepts it, which "
            + "is one of the three places the documentation and the server disagree."),

    /**
     * Compression, and the one setting here that cannot be taken back.
     * <p>
     * All four quantizers were measured switching on against a populated collection, which is the
     * only way to measure them: an empty one accepts the configuration and never trains, which
     * reads as success and is not. Weaviate's own documentation is equally clear that quantization
     * cannot be disabled once set, so this is a one-way door and the UI treats it as one.
     * <p>
     * Its path is the index itself rather than a leaf, because the value chooses the path:
     * picking {@code rq} writes {@code rq.enabled}. {@code WeaviateConfigUpdateCommand} does that
     * translation, at the one place that knows both the choice and the document.
     */
    QUANTIZER(
        Group.VECTOR_INDEX, Kind.QUANTIZER, "", "Quantization",
        "Compresses stored vectors, trading recall for memory. RQ is the one Weaviate recommends. "
            + "This cannot be undone: the server offers no way back to uncompressed, so the only "
            + "way out is to rebuild the collection.",
        List.of("none", "rq", "bq", "sq", "pq"));

    /**
     * The choice standing for "not compressed".
     * <p>
     * Declared after the constants because an enum constant cannot reference a static field of its
     * own class in its arguments, so the list above spells it literally.
     */
    public static final String NO_QUANTIZER = "none";

    /** Which part of the definition a setting belongs to, and so which box it is drawn in. */
    public enum Group {
        GENERAL("General"),
        INVERTED_INDEX("Inverted index"),
        REPLICATION("Replication"),
        /** Repeated once per vector, since a collection may have several with separate indexes. */
        VECTOR_INDEX("Vector index");

        private final String label;

        Group(String label) {
            this.label = label;
        }

        @NotNull
        public String getLabel() {
            return label;
        }

        public boolean isPerVector() {
            return this == VECTOR_INDEX;
        }
    }

    /** What kind of control the setting needs, and how its value is read back out of the form. */
    public enum Kind {
        TEXT, INTEGER, LONG, DECIMAL, BOOLEAN, CHOICE, WORD_LIST,
        /** A choice like {@link #CHOICE}, but one-way: see {@link WeaviateConfigSetting#QUANTIZER}. */
        QUANTIZER
    }

    private final Group group;
    private final Kind kind;
    private final String path;
    private final String label;
    private final String tip;
    private final List<String> choices;
    private final Double minimum;
    private final Double maximum;

    WeaviateConfigSetting(Group group, Kind kind, String path, String label, String tip) {
        this(group, kind, path, label, tip, List.of(), null, null);
    }

    WeaviateConfigSetting(
        Group group, Kind kind, String path, String label, String tip, List<String> choices
    ) {
        this(group, kind, path, label, tip, choices, null, null);
    }

    WeaviateConfigSetting(
        Group group, Kind kind, String path, String label, String tip, Double min, Double max
    ) {
        this(group, kind, path, label, tip, List.of(), min, max);
    }

    WeaviateConfigSetting(
        Group group, Kind kind, String path, String label, String tip,
        List<String> choices, Double minimum, Double maximum
    ) {
        this.group = group;
        this.kind = kind;
        this.path = path;
        this.label = label;
        this.tip = tip;
        this.choices = choices;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    @NotNull
    public Group getGroup() {
        return group;
    }

    @NotNull
    public Kind getKind() {
        return kind;
    }

    @NotNull
    public String getLabel() {
        return label;
    }

    @NotNull
    public String getTip() {
        return tip;
    }

    @NotNull
    public List<String> getChoices() {
        return choices;
    }

    @Nullable
    public Double getMinimum() {
        return minimum;
    }

    @Nullable
    public Double getMaximum() {
        return maximum;
    }

    /**
     * Where this setting lives in a definition.
     *
     * @param vectorName the vector whose index is being configured, for the per-vector group;
     *                   ignored by every other group. An empty name is the older unnamed index.
     */
    @NotNull
    public String pathIn(@NotNull WeaviateConfigDocument document, @NotNull String vectorName) {
        if (!group.isPerVector()) {
            return path;
        }
        String index = document.vectorIndexPath(vectorName);
        // An empty path means the index itself, which is what QUANTIZER addresses: the value it
        // carries decides the leaf underneath.
        return path.isEmpty() ? index : index + "." + path;
    }

    /**
     * Which quantizer a definition currently has, or {@link #NO_QUANTIZER}.
     * <p>
     * Read by scanning for one that is switched on rather than by trusting a single field: the
     * server reports all four, each with its own {@code enabled} flag, and the one that is true is
     * the answer.
     */
    @NotNull
    public static String quantizerIn(
        @NotNull WeaviateConfigDocument document, @NotNull String vectorName
    ) {
        String index = document.vectorIndexPath(vectorName);
        for (String quantizer : List.of("rq", "bq", "sq", "pq")) {
            if (Boolean.TRUE.equals(document.getBoolean(index + "." + quantizer + ".enabled"))) {
                return quantizer;
            }
        }
        return NO_QUANTIZER;
    }

    /** The settings of one group, in declaration order. */
    @NotNull
    public static List<WeaviateConfigSetting> of(@NotNull Group group) {
        List<WeaviateConfigSetting> result = new java.util.ArrayList<>();
        for (WeaviateConfigSetting setting : values()) {
            if (setting.group == group) {
                result.add(setting);
            }
        }
        return result;
    }
}
