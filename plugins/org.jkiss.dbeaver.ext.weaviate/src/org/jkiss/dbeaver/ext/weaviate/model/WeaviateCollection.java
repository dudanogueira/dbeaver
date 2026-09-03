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

import com.google.gson.JsonElement;
import io.weaviate.client6.v1.api.collections.CollectionConfig;
import io.weaviate.client6.v1.api.collections.CollectionHandle;
import io.weaviate.client6.v1.api.collections.Property;
import io.weaviate.client6.v1.api.collections.Reranker;
import io.weaviate.client6.v1.api.collections.VectorConfig;
import io.weaviate.client6.v1.api.collections.WeaviateObject;
import io.weaviate.client6.v1.api.collections.aggregate.AggregateResponse;
import io.weaviate.client6.v1.api.collections.pagination.Paginator;
import io.weaviate.client6.v1.api.collections.data.DeleteManyResponse;
import io.weaviate.client6.v1.api.collections.generate.GenerativeResponse;
import io.weaviate.client6.v1.api.collections.generate.GenerativeResponseGroup;
import io.weaviate.client6.v1.api.collections.generate.GenerativeResponseGrouped;
import io.weaviate.client6.v1.api.collections.generate.GenerativeTask;
import io.weaviate.client6.v1.api.collections.generate.GenerativeProvider;
import io.weaviate.client6.v1.api.collections.generate.GenerativeObject;
import io.weaviate.client6.v1.api.collections.generate.WeaviateGenerateClient;
import io.weaviate.client6.v1.api.collections.query.Bm25;
import io.weaviate.client6.v1.api.collections.query.FetchObjects;
import io.weaviate.client6.v1.api.collections.query.Filter;
import io.weaviate.client6.v1.api.collections.query.GroupBy;
import io.weaviate.client6.v1.api.collections.query.Hybrid;
import io.weaviate.client6.v1.api.collections.query.NearObject;
import io.weaviate.client6.v1.api.collections.query.QueryOperator;
import io.weaviate.client6.v1.api.collections.query.NearText;
import io.weaviate.client6.v1.api.collections.query.NearVector;
import io.weaviate.client6.v1.internal.ObjectBuilder;
import io.weaviate.client6.v1.api.collections.tenants.Tenant;
import io.weaviate.client6.v1.api.tokenize.TokenizeResponse;
import io.weaviate.client6.v1.api.collections.query.Metadata;
import io.weaviate.client6.v1.api.collections.query.NearVectorTarget;
import io.weaviate.client6.v1.api.collections.query.QueryObjectGrouped;
import io.weaviate.client6.v1.api.collections.query.QueryResponse;
import io.weaviate.client6.v1.api.collections.query.QueryResponseGroup;
import io.weaviate.client6.v1.api.collections.query.QueryResponseGrouped;
import io.weaviate.client6.v1.api.collections.query.Rerank;
import io.weaviate.client6.v1.api.collections.query.SortBy;
import io.weaviate.client6.v1.api.collections.query.Target;
import io.weaviate.client6.v1.api.collections.query.WeaviateQueryClient;
import io.weaviate.client6.v1.internal.json.JSON;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPRefreshableObject;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeConstraint;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.struct.DBSAttributeBase;
import org.jkiss.dbeaver.model.impl.edit.SQLDatabasePersistActionComment;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.exec.DBCExecutionSource;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.impl.local.LocalResultSet;
import org.jkiss.dbeaver.model.impl.local.LocalStatement;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSDataManipulator;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAssociation;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.DBSEntityType;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.DBPPlatformUI;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.TreeMap;

public class WeaviateCollection implements DBSEntity, DBSDataManipulator, DBPRefreshableObject {

    private static final Log log = Log.getLog(WeaviateCollection.class);

    private static final String[] SUPPORTED_FEATURES = new String[]{
        FEATURE_DATA_SELECT,
        FEATURE_DATA_COUNT,
        FEATURE_DATA_FILTER,
        FEATURE_DATA_DELETE,
    };

    private static final org.jkiss.dbeaver.Log queryLog = org.jkiss.dbeaver.Log.getLog(WeaviateCollection.class);

    private final WeaviateDataSource dataSource;
    /** Replaced once by {@link #markPersisted} when a pending collection is created on the server. */
    private volatile CollectionConfig config;
    private volatile boolean persisted;
    private volatile List<WeaviateProperty> attributes;
    private static final String TENANT_REQUIRED =
        "This collection is multi-tenant. Right-click the collection and choose "
            + "\"Select Tenant...\" to pick one.";

    private WeaviateUuidAttribute uuidAttribute;
    private WeaviateUuidConstraint uuidConstraint;

    private volatile String lastQueryError;
    /**
     * The last grouped-task generative output, or null. One text for the whole result set, so
     * it has no row to live on -- the Query panel shows it in the Generative section, polling
     * this after each run exactly as it polls {@link #lastQueryError}.
     */
    private volatile String lastGenerativeGroupedResult;
    /**
     * The last read's query profile, or null when none was asked for. Like the grouped generative
     * text it belongs to the run rather than to any row, so the panel polls it after each read.
     */
    private volatile WeaviateQueryProfile lastQueryProfile;
    /**
     * Rerank scores of the last read, by uuid. Filled only by a reranked near_* search; the row
     * mapper reads it because the score reaches us outside the typed object.
     */
    private volatile Map<String, Float> lastRerankScores = Collections.emptyMap();
    private volatile String pendingSchemaJson;
    private volatile List<WeaviateJsonNode> definitionNodes;
    /** Tenant listing, the tenant node cache, and the state changes that invalidate it. */
    private final WeaviateTenancy tenancy;

    public WeaviateCollection(@NotNull WeaviateDataSource dataSource, @NotNull CollectionConfig config) {
        this(dataSource, config, true);
    }

    public WeaviateCollection(
        @NotNull WeaviateDataSource dataSource,
        @NotNull CollectionConfig config,
        boolean persisted
    ) {
        this.dataSource = dataSource;
        this.config = config;
        this.persisted = persisted;
        this.tenancy = new WeaviateTenancy(this, dataSource);
    }

    /**
     * An unsaved collection with the given name and no properties yet.
     * Its real definition comes from {@link #getPendingSchemaJson()} when the create runs.
     */
    @NotNull
    public static WeaviateCollection createNew(@NotNull WeaviateDataSource dataSource, @NotNull String name) {
        return new WeaviateCollection(dataSource, CollectionConfig.of(name), false);
    }

    /**
     * Schema JSON staged for an unsaved collection.
     * <p>
     * Creation is driven by a JSON definition rather than a field-by-field form, so the
     * definition has to survive from the configurator dialog until the create command runs.
     * It is held as text because the configurator lives in the UI bundle, which cannot see the
     * shaded {@code io.weaviate.*} classes that {@link CollectionConfig} is built from -
     * {@link WeaviateSchemaJson} does the conversion on this side of the boundary.
     */
    @Nullable
    public String getPendingSchemaJson() {
        return pendingSchemaJson;
    }

    public void setPendingSchemaJson(@Nullable String json) {
        this.pendingSchemaJson = json;
    }

    /**
     * This collection's definition as REST schema JSON, suitable for export or for seeding
     * a new collection.
     */
    @NotNull
    public String toSchemaJson() {
        return WeaviateSchemaJson.toJson(config);
    }

    @Override
    public boolean isPersisted() {
        return persisted;
    }

    /**
     * Rename an unsaved collection so the navigator label matches the name in the staged
     * definition. Only meaningful before creation - Weaviate cannot rename a live collection.
     */
    public void rename(@NotNull String newName) {
        if (persisted) {
            throw new IllegalStateException("Weaviate collections cannot be renamed once created");
        }
        // A pending collection's config is only a placeholder carrying the name for the tree
        // label; the definition that actually gets created comes from the staged JSON.
        this.config = CollectionConfig.of(newName);
    }

    /**
     * Adopt the definition the server actually stored and become a live collection.
     * <p>
     * Called after a successful create. The <em>same</em> instance is promoted rather than
     * replaced by a freshly loaded one, because DBeaver looks up the navigator node for the exact
     * object returned by {@code createNewObject} (see {@code DBNModel#getNodeByObject}, which is
     * keyed on the object itself). Swapping in a different instance leaves that lookup unmatched
     * and the create fails with "Can't find node corresponding to new object".
     *
     * @param serverConfig definition read back from the server, which includes defaults the
     *                     submitted document left out
     */
    public void markPersisted(@NotNull CollectionConfig serverConfig) {
        this.config = serverConfig;
        this.attributes = null;
        this.pendingSchemaJson = null;
        this.definitionNodes = null;
        this.persisted = true;
    }

    @NotNull
    @Override
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 1)
    public String getName() {
        return config.collectionName();
    }

    @Nullable
    @Override
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 2)
    public String getDescription() {
        return config.description();
    }

    @NotNull
    @Override
    public DBSEntityType getEntityType() {
        return DBSEntityType.TABLE;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return dataSource;
    }

    @Override
    public DBSObject getParentObject() {
        return dataSource.getContainer();
    }

    /**
     * The collection's declared properties, without the uuid attribute.
     * Callers that need what the grid shows want {@link #getAttributes} instead.
     */
    @NotNull
    public List<WeaviateProperty> getProperties(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (attributes == null) {
            synchronized (this) {
                if (attributes == null) {
                    attributes = loadAttributes();
                }
            }
        }
        return attributes;
    }

    private List<WeaviateProperty> loadAttributes() {
        List<Property> props = config.properties();
        if (props == null || props.isEmpty()) {
            return Collections.emptyList();
        }
        // Ordinal 0 is reserved for the uuid attribute, which is not a declared property but is
        // a real column of every result -- see getAttributesWithId().
        List<WeaviateProperty> result = new ArrayList<>(props.size());
        for (int i = 0; i < props.size(); i++) {
            result.add(new WeaviateProperty(this, props.get(i), i + 1));
        }
        return result;
    }

    /**
     * The uuid attribute, kept as a single instance so identity comparisons hold.
     */
    @NotNull
    public synchronized WeaviateUuidAttribute getUuidAttribute() {
        if (uuidAttribute == null) {
            uuidAttribute = new WeaviateUuidAttribute(this);
        }
        return uuidAttribute;
    }

    /**
     * Declared properties preceded by the uuid attribute.
     * <p>
     * DBeaver needs uuid in the entity's attributes for two reasons: the grid renders the column
     * from them, and {@link #getConstraints} names it as the primary key, which is what allows a
     * selected row to be deleted.
     */
    @NotNull
    @Override
    public List<? extends DBSEntityAttribute> getAttributes(@NotNull DBRProgressMonitor monitor) throws DBException {
        List<DBSEntityAttribute> all = new ArrayList<>();
        all.add(getUuidAttribute());
        all.addAll(getProperties(monitor));
        return all;
    }

    @Override
    public DBSEntityAttribute getAttribute(@NotNull DBRProgressMonitor monitor, @NotNull String attributeName) throws DBException {
        return DBUtils.findObject(getAttributes(monitor), attributeName);
    }

    @Override
    public Collection<? extends DBSEntityConstraint> getConstraints(@NotNull DBRProgressMonitor monitor) {
        if (uuidConstraint == null) {
            uuidConstraint = new WeaviateUuidConstraint(this, getUuidAttribute());
        }
        return List.of(uuidConstraint);
    }

    @Override
    public Collection<? extends DBSEntityAssociation> getAssociations(@NotNull DBRProgressMonitor monitor) {
        return Collections.emptyList();
    }

    @Override
    public Collection<? extends DBSEntityAssociation> getReferences(@NotNull DBRProgressMonitor monitor) {
        return Collections.emptyList();
    }

    public CollectionConfig getConfig() {
        return config;
    }

    // ---- Config sub-folders ----------------------------------------------------------------
    // Each method uses reflection on the underlying record so future-added fields show up.

    /**
     * Which nodes hold a replica of each of this collection's shards.
     * <p>
     * Read fresh rather than cached: it is the answer to "where can this move to", and a stale
     * answer there would offer a target that already holds the shard -- which the server refuses.
     * The list is one row per shard, so the cost is small.
     * <p>
     * Empty when replica movement is switched off, rather than an error: the folder is a
     * description of where things are, and that is still worth nothing rather than a stack trace.
     */
    @NotNull
    @Association
    public List<WeaviateShardReplicas> getShardReplicas(@NotNull DBRProgressMonitor monitor) {
        List<WeaviateShardReplicas> result = new ArrayList<>();
        if (!(dataSource instanceof WeaviateDataSource ds) || !ds.isRestApiAvailable()) {
            return result;
        }
        try {
            for (WeaviateReplicationRest.ShardReplicas shard
                : WeaviateReplicationRest.shardingState(ds, getName()).shards()) {
                result.add(new WeaviateShardReplicas(this, shard,
                    ds.getPlacementTracker().note(
                        getName() + "/" + shard.shard(), shard.replicas())));
            }
            result.sort((a, b) -> a.getShardName().compareToIgnoreCase(b.getShardName()));
        } catch (DBException e) {
            log.debug("Cannot read the sharding state of " + getName(), e);
        }
        return result;
    }

    @Association
    public List<WeaviateMetadataField> getReplicationFields(@NotNull DBRProgressMonitor monitor) {
        return WeaviateRecordIntrospect.toFields(this, config.replication());
    }

    /**
     * How many copies of each shard this collection asks for, or null when the server never said.
     * <p>
     * Read straight off the config already in memory, so it costs nothing and can be asked while
     * building a menu or a panel. Null is not one: a server that omits the block has not told us
     * the factor is one, and a control hidden on a guess is worse than one shown needlessly --
     * the same direction {@link WeaviateVersions} guesses in.
     */
    @Nullable
    public Integer getReplicationFactor() {
        return config.replication() == null ? null : config.replication().replicationFactor();
    }

    /**
     * Whether each shard has more than one copy, which is the condition under which a read
     * consistency level means anything at all.
     * <p>
     * The factor rather than the cluster's node count, which is the other way to ask it. A
     * three-node cluster holding an unreplicated collection has exactly one copy of every shard,
     * so ONE, QUORUM and ALL are the same read there too -- and the node count would say
     * otherwise. It is also free, where the node list is a request that walks every shard.
     */
    public boolean isReplicated() {
        Integer factor = getReplicationFactor();
        return factor == null || factor > 1;
    }

    @Association
    public List<WeaviateMetadataField> getShardingFields(@NotNull DBRProgressMonitor monitor) {
        return WeaviateRecordIntrospect.toFields(this, config.sharding());
    }

    @Association
    public List<WeaviateMetadataField> getMultiTenancyFields(@NotNull DBRProgressMonitor monitor) {
        return WeaviateRecordIntrospect.toFields(this, config.multiTenancy());
    }

    @Association
    public List<WeaviateMetadataField> getInvertedIndexFields(@NotNull DBRProgressMonitor monitor) {
        return WeaviateRecordIntrospect.toFields(this, config.invertedIndex());
    }

    @Association
    public List<WeaviateMetadataField> getObjectTtlFields(@NotNull DBRProgressMonitor monitor) {
        return WeaviateRecordIntrospect.toFields(this, config.objectTtl());
    }

    /**
     * The aliases that resolve to this collection.
     * <p>
     * A filtered view of the connection-wide list rather than a second one: an alias belongs to
     * the server, and two caches for the same objects can disagree. The datasource holds the cache
     * -- see {@code WeaviateAliases#forCollection} for why the filtering is done here rather than
     * asked of the server.
     */
    @NotNull
    @Association
    public List<WeaviateAlias> getAliases(@NotNull DBRProgressMonitor monitor) throws DBException {
        return dataSource instanceof WeaviateDataSource ds
            ? ds.getCollectionAliases(monitor, getName())
            : List.of();
    }

    /**
     * Kind of the collection's configured generative module ("OPENAI", ...), or null when none
     * is. What a generative query uses when no runtime provider override is sent -- the Query
     * panel names it in the provider dropdown's default entry.
     */
    @Nullable
    public String getGenerativeModuleKind() {
        var module = config.generativeModule();
        if (module == null) {
            return null;
        }
        Object kind = module._kind();
        return kind == null ? null : kind.toString();
    }

    @Association
    public List<WeaviateMetadataField> getGenerativeFields(@NotNull DBRProgressMonitor monitor) {
        return WeaviateRecordIntrospect.toFields(this, config.generativeModule());
    }

    /**
     * The collection's definition exactly as the server reports it, as a browsable tree.
     * <p>
     * Read over REST rather than from {@link #config}, because that object was produced by the
     * client's deserializer and therefore only contains fields the bundled client knows how to
     * model. Anything newer or unrecognised - which is precisely what the typed folders cannot
     * show - is present here.
     * <p>
     * Cached after the first expansion; a navigator refresh rebuilds the collection and so
     * re-reads it.
     */
    @Association
    @NotNull
    public List<WeaviateJsonNode> getDefinitionNodes(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (definitionNodes == null) {
            synchronized (this) {
                if (definitionNodes == null) {
                    definitionNodes = loadDefinitionNodes();
                }
            }
        }
        return definitionNodes;
    }

    /**
     * The definition as the server stores it, verbatim.
     * <p>
     * The source for both the Raw Definition folder and the configuration editor, and the document
     * an edit is applied to -- so what this build cannot model still travels back untouched.
     * <p>
     * Falls back to the client's narrower view on an OIDC connection, where a direct REST call
     * cannot be authenticated. That fallback is fine for reading and not for writing: see
     * {@link #isConfigEditable()}.
     */
    @NotNull
    public String readRawDefinition() throws DBException {
        if (dataSource.isRestApiAvailable()) {
            return WeaviateSchemaRest.fetchCollectionSchema(dataSource, getName());
        }
        queryLog.debug("Direct REST unavailable on this connection; showing the definition of '"
            + getName() + "' as understood by the client");
        return toSchemaJson();
    }

    /**
     * Whether configuration can be saved on this connection, as opposed to merely displayed.
     * <p>
     * A configuration change is a {@code PUT /v1/schema}, and REST cannot be authenticated on an
     * OIDC connection -- the token is minted inside the client with no public accessor. Same limit
     * RBAC and replication carry, and the same one that already makes creating a collection refuse
     * there.
     */
    public boolean isConfigEditable() {
        return dataSource.isRestApiAvailable();
    }

    @NotNull
    private List<WeaviateJsonNode> loadDefinitionNodes() throws DBException {
        if (!persisted) {
            return Collections.emptyList();
        }
        String rawJson = readRawDefinition();
        JsonElement root;
        try {
            root = JSON.toJsonElement(rawJson);
        } catch (RuntimeException e) {
            throw new DBException("Weaviate returned an unreadable schema for '" + getName() + "'", e);
        }
        if (root == null || !root.isJsonObject()) {
            return Collections.emptyList();
        }
        return WeaviateJsonNode.fromObject(this, dataSource, root.getAsJsonObject());
    }

    @Association
    public List<WeaviateVectorizer> getVectorizers(@NotNull DBRProgressMonitor monitor) {
        Map<String, VectorConfig> vectors = config.vectors();
        if (vectors == null || vectors.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, VectorConfig> sorted = new TreeMap<>(vectors);
        List<WeaviateVectorizer> result = new ArrayList<>(sorted.size());
        for (Map.Entry<String, VectorConfig> e : sorted.entrySet()) {
            result.add(new WeaviateVectorizer(this, e.getKey(), e.getValue()));
        }
        return result;
    }

    @Association
    public List<WeaviateReranker> getRerankers(@NotNull DBRProgressMonitor monitor) {
        List<Reranker> rerankers = config.rerankerModules();
        if (rerankers == null || rerankers.isEmpty()) {
            return Collections.emptyList();
        }
        List<WeaviateReranker> result = new ArrayList<>(rerankers.size());
        for (int i = 0; i < rerankers.size(); i++) {
            result.add(new WeaviateReranker(this, i, rerankers.get(i)));
        }
        return result;
    }

    // ---- DBSDataContainer ------------------------------------------------------------------

    @NotNull
    @Override
    public String[] getSupportedFeatures() {
        return SUPPORTED_FEATURES;
    }

    @NotNull
    @Override
    public DBCStatistics readData(
        @Nullable DBCExecutionSource source,
        @NotNull DBCSession session,
        @NotNull DBDDataReceiver dataReceiver,
        @Nullable DBDDataFilter dataFilter,
        long firstRow,
        long maxRows,
        long flags,
        int fetchSize
    ) throws DBException {
        DBCStatistics statistics = new DBCStatistics();
        DBRProgressMonitor monitor = session.getProgressMonitor();

        WeaviateQuerySpec spec = getQuerySpec();
        boolean armed = dataSource.isRunArmed(getName());
        if (!armed && !spec.isAutoRunSafe()) {
            // Opening a viewer re-reads whatever was remembered, and the remembered spec may be
            // a search that re-embeds or a generative task that calls a paid model per object.
            // Unarmed -- no Run pressed for this viewer -- the spec demotes to a plain fetch,
            // keeping its inputs so the panel still shows them and Run brings the search back.
            spec = spec.demotedToFetch();
            setQuerySpec(spec);
        }
        // What actually executes: on an unarmed read the generative task is stripped -- it is
        // the one thing FETCH would still fire -- while the stored spec keeps it configured.
        WeaviateQuerySpec exec = armed ? spec : spec.withoutGenerative();
        List<WeaviateProperty> attributes = getProperties(monitor);
        List<String> declaredVectors = getVectorNames();
        List<String> vectorNames = includeVectors(session, spec)
            ? WeaviateQueryRequest.narrowToTargets(spec, declaredVectors)
            : Collections.emptyList();
        // Derived from what the collection declares, not from what this query asked for: the
        // column name collapses to a bare "_vector" when a collection has only one, so reading it
        // off the narrowed list would relabel _vector_title as _vector the moment a user targeted
        // one of three.
        boolean singleVector = declaredVectors.size() <= 1;

        List<String> columnNames = new ArrayList<>(attributes.size() + 3 + vectorNames.size());
        columnNames.add(WeaviateColumns.UUID);
        for (WeaviateProperty p : attributes) {
            columnNames.add(p.getName());
        }
        for (String vectorName : vectorNames) {
            columnNames.add(WeaviateColumns.vectorColumn(vectorName, singleVector));
        }
        // Only the metric this mode actually produces: a plain fetch has neither, and showing
        // an always-empty _score or _distance column just crowds out the real properties.
        // Grouping costs three of the four metrics. An object inside a group carries only
        // `distance`, `id` and `vector` -- that is the server's whole group-hits type, and it is
        // visible directly in the GraphQL schema: DBeaverGroupFixtureAdditionalGroupHitsAdditional
        // has three fields where the ungrouped _additional has thirteen. So score, explainScore
        // and certainty are unavailable through any API, not merely unrequested; asking for them
        // by name over gRPC returns scorePresent=false. Offering the columns anyway would put
        // permanently empty ones next to the real ones.
        //
        // (The same schema is why a grouped object has no timestamps, handled further down.)
        boolean groupedMetadata = exec.isGrouped();
        if (spec.getMode().hasScore() && !groupedMetadata) {
            columnNames.add(WeaviateColumns.SCORE);
        }
        if (spec.getMode().hasExplainScore() && spec.isExplainScore() && !groupedMetadata) {
            columnNames.add(WeaviateColumns.EXPLAIN_SCORE);
        }
        if (spec.getMode().hasDistance()) {
            columnNames.add(WeaviateColumns.DISTANCE);
        }
        if (spec.isWithCertainty() && spec.getMode().supportsCertainty() && !groupedMetadata) {
            columnNames.add(WeaviateColumns.CERTAINTY);
        }
        if (WeaviateQueryRequest.hasRerankScore(exec)) {
            columnNames.add(WeaviateColumns.RERANK_SCORE);
        }
        // From exec for the same reason the generative columns are: a demoted read is not grouped,
        // and the columns must describe what actually comes back.
        if (exec.isGrouped()) {
            columnNames.add(WeaviateColumns.GROUP);
            if (exec.getGroupBy().isWithGroupStats()) {
                columnNames.add(WeaviateColumns.GROUP_COUNT);
                columnNames.add(WeaviateColumns.GROUP_MIN_DISTANCE);
                columnNames.add(WeaviateColumns.GROUP_MAX_DISTANCE);
            }
        }
        // Timestamps are dropped on a grouped read rather than shown empty: the client's grouped
        // object type carries no creation or update time at all, so the checkboxes would otherwise
        // add two columns that can never be filled.
        if (spec.isWithCreated() && !exec.isGrouped()) {
            columnNames.add(WeaviateColumns.CREATED);
        }
        if (spec.isWithUpdated() && !exec.isGrouped()) {
            columnNames.add(WeaviateColumns.UPDATED);
        }
        // From exec, not spec: on an unarmed read the task is stripped from execution, and the
        // columns must describe what actually comes back.
        if (exec.getGenerative() != null && exec.getGenerative().getSinglePrompt() != null) {
            columnNames.add(WeaviateColumns.GENERATED);
            if (exec.getGenerative().isReturnMetadata()) {
                columnNames.add(WeaviateColumns.GENERATIVE_META);
            }
        }

        // A multi-tenant collection has no queryable "all tenants" view; Weaviate errors out.
        // Asked once, when no tenant has been chosen yet -- asking on every read means a dialog
        // after each query and refresh. Changing it afterwards is the Query panel's tenant
        // dropdown, or "Select Tenant..." on the collection.
        if (isMultiTenant() && CommonUtils.isEmpty(spec.getTenant())) {
            String chosen = tenancy.promptForTenant(session, monitor, spec);
            if (chosen == null) {
                // Declined, and nothing to fall back on. Reported like a failed query -- banner
                // and an empty grid -- rather than thrown, so the result tab survives.
                lastQueryError = TENANT_REQUIRED;
                statistics.setQueryText(TENANT_REQUIRED);
                return statistics;
            }
            spec = spec.withTenant(chosen);
            exec = exec.withTenant(chosen);
            // Remember it so the row count, the panel and any delete use the same tenant.
            setQuerySpec(spec);
        }

        // Filter translation rejects anything it cannot express rather than widening the result
        // set. That rejection is a RuntimeException, so it has to be caught here: letting it
        // escape would tear down the result tab and take the Query panel with it, exactly like
        // a failed query used to.
        Filter filter;
        try {
            Filter columnHeaderFilter = WeaviateFilterTranslator.translate(dataFilter);
            Filter customFilter = WeaviateFilterTranslator.translateRows(spec.getFilterRows(), spec.isAnyFilter());
            filter = WeaviateFilterTranslator.and(columnHeaderFilter, customFilter);
        } catch (WeaviateUnsupportedFilterException e) {
            lastQueryError = e.getMessage();
            statistics.setQueryText("Filter not applied — " + e.getMessage());
            return statistics;
        }
        List<SortBy> sortBy = spec.rankedResults() ? Collections.emptyList() : WeaviateQueryRequest.buildSortBy(dataFilter, attributes);
        int limit = maxRows > 0 ? (int) Math.min(maxRows, Integer.MAX_VALUE) : 0;
        int offset = firstRow > 0 ? (int) Math.min(firstRow, Integer.MAX_VALUE) : 0;

        String queryText = WeaviateQueryDescription.describeQuery(exec, filter, sortBy, limit, offset);
        statistics.setQueryText(queryText);

        // An unbounded plain fetch is an export ("extract in a single query"). Offset paging cannot
        // serve it: Weaviate refuses offsets past QUERY_MAXIMUM_RESULTS (10000 by default), so a
        // large collection would fail partway through. The cursor paginator walks the whole
        // collection server-side instead, with no such ceiling.
        //
        // Not when a filter is set. Cursor pagination is built on object ids, and Weaviate
        // documents `after` as incompatible with filters -- it does not reject the filter, it
        // ignores it, so a filtered export would quietly hand back the whole collection. The
        // docs prescribe offset paging for exactly this case, and a filtered slice is far less
        // likely to reach the offset ceiling that this branch exists to avoid.
        if (maxRows <= 0
            && exec.getMode() == WeaviateQueryMode.FETCH
            && exec.getGenerative() == null
            && !exec.isGrouped()
            && filter == null
        ) {
            // (Generative fetches never take this path: the paginator cannot carry a task, and
            // prompting a model once per object across an entire collection is not an export
            // anyone means to run by accident. Grouped fetches are out for a plainer reason --
            // the paginator cannot carry a GroupBy either, so an export would silently hand back
            // the whole collection ungrouped.)
            return readAllViaCursor(
                source, session, dataReceiver, statistics, exec, filter,
                attributes, columnNames, vectorNames, singleVector, queryText, firstRow);
        }

        long startTime = System.currentTimeMillis();
        List<Object[]> rows;
        String defaultVectorName = singleVector && !vectorNames.isEmpty() ? vectorNames.get(0) : null;
        try {
            rows = new ArrayList<>();
            // Cleared per read: a stale map would decorate the next query's rows with the last
            // one's scores wherever a uuid happened to repeat.
            lastRerankScores = Collections.emptyMap();
            // Cleared per read as well: a profile left over from the previous query describes work
            // that has nothing to do with the rows now on screen.
            lastQueryProfile = null;
            if (exec.isGrouped()) {
                appendGroupedRows(exec, filter, sortBy, limit, offset,
                    columnNames, defaultVectorName, rows);
            } else if (exec.getGenerative() != null) {
                GenerativeResponse<Map<String, Object>> response =
                    executeGenerativeQuery(exec, filter, sortBy, limit, offset);
                // No profile on this path: GenerativeResponse exposes no queryProfile(), though
                // the reply carries one. See WeaviateQueryProfile.
                lastGenerativeGroupedResult =
                    response.generative() == null ? null : response.generative().text();
                for (GenerativeObject<Map<String, Object>> obj : response.objects()) {
                    rows.add(WeaviateRowMapper.toRow(columnNames, obj, defaultVectorName,
                        lastRerankScores.get(obj.uuid())));
                }
            } else {
                lastGenerativeGroupedResult = null;
                QueryResponse<Map<String, Object>> response =
                    executeQuery(exec, filter, sortBy, limit, offset);
                lastQueryProfile = WeaviateQueryProfile.from(response.queryProfile());
                for (WeaviateObject<Map<String, Object>> obj : response.objects()) {
                    rows.add(WeaviateRowMapper.toRow(columnNames, obj, defaultVectorName,
                        lastRerankScores.get(obj.uuid())));
                }
            }
            lastQueryError = null;
        } catch (IllegalStateException e) {
            // Pre-flight validation (missing query text / empty vector). The user is still
            // composing the query in the panel, so report it inline via the banner and render
            // an empty grid rather than interrupting with a modal error.
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            lastQueryError = msg;
            statistics.setQueryText(queryText + " — " + msg);
            return statistics;
        } catch (Exception e) {
            // A real failure (transport, auth, rejected filter, mismatched vector length).
            //
            // Deliberately reported without throwing DBCException: that makes DBeaver discard
            // the result tab, and the Weaviate Query panel goes with it -- so a bad query
            // destroys the very controls needed to correct it and retry. The failure is still
            // loud rather than swallowed: a red banner in the panel, a workbench warning
            // notification, "ERROR" in the query text, and a logged warning.
            String msg = describeFailure(e, spec.getTenant());
            lastQueryError = msg;
            queryLog.warn("Weaviate query failed for collection " + getName() + ": " + msg, e);
            statistics.setQueryText(queryText + " — ERROR: " + msg);
            return statistics;
        }
        statistics.setExecuteTime(System.currentTimeMillis() - startTime);
        if (monitor.isCanceled()) {
            return statistics;
        }

        try (LocalStatement statement = new LocalStatement(session, queryText)) {
            statement.setStatementSource(source);
            LocalResultSet<LocalStatement> resultSet = new LocalResultSet<>(session, statement);
            populateColumns(resultSet, attributes, vectorNames, singleVector, exec);
            for (Object[] row : rows) {
                resultSet.addRow(row);
            }
            DBDDataReceiver.startFetchWorkflow(dataReceiver, session, resultSet, firstRow, maxRows);
            DBDDataReceiver.fetchRowsWithStatistics(dataReceiver, session, resultSet, statistics);
        }
        return statistics;
    }

    /**
     * Stream an entire collection through Weaviate's cursor pagination, for exports.
     * <p>
     * Rows are handed to the receiver as they arrive rather than collected first, so memory does
     * not scale with collection size.
     */
    @NotNull
    private DBCStatistics readAllViaCursor(
        @Nullable DBCExecutionSource source,
        @NotNull DBCSession session,
        @NotNull DBDDataReceiver dataReceiver,
        @NotNull DBCStatistics statistics,
        @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter,
        @NotNull List<WeaviateProperty> attributes,
        @NotNull List<String> columnNames,
        @NotNull List<String> vectorNames,
        boolean singleVector,
        @NotNull String queryText,
        long firstRow
    ) throws DBException {
        DBRProgressMonitor monitor = session.getProgressMonitor();
        long startTime = System.currentTimeMillis();
        String defaultVectorName = singleVector && !vectorNames.isEmpty() ? vectorNames.get(0) : null;
        long skip = Math.max(firstRow, 0);
        long fetched = 0;

        try (LocalStatement statement = new LocalStatement(session, queryText)) {
            statement.setStatementSource(source);
            LocalResultSet<LocalStatement> resultSet = new LocalResultSet<>(session, statement);
            populateColumns(resultSet, attributes, vectorNames, singleVector, spec);
            try {
                Paginator<Map<String, Object>> paginator =
                    handle(spec.getTenant()).paginate(b -> {
                        // No filters here: readData only routes an unfiltered read this way,
                        // because `after` cannot be combined with them (see the branch above).
                        // includeVector and returnMetadata do survive cursor paging.
                        if (spec.isIncludeVector()) b.includeVector();
                        // The export path must return the same columns the grid shows, so the
                        // opt-in metadata rides along here as well.
                        List<Metadata> extra = WeaviateQueryRequest.extraMetadata(spec);
                        if (!extra.isEmpty()) b.returnMetadata(extra);
                        return b;
                    });
                for (WeaviateObject<Map<String, Object>> obj : paginator) {
                    if (monitor.isCanceled()) {
                        break;
                    }
                    if (skip > 0) {
                        skip--;
                        continue;
                    }
                    resultSet.addRow(WeaviateRowMapper.toRow(columnNames, obj, defaultVectorName));
                    fetched++;
                }
                lastQueryError = null;
            } catch (Exception e) {
                // Same reasoning as readData: report, do not throw. Falling through also
                // delivers the rows that did arrive before the cursor failed, instead of
                // discarding a partial page the user could still use.
                String msg = describeFailure(e, spec.getTenant());
                lastQueryError = msg;
                queryLog.warn("Weaviate cursor read failed for collection " + getName() + ": " + msg, e);
                statistics.setQueryText(queryText + " — ERROR: " + msg);
            }
            statistics.setExecuteTime(System.currentTimeMillis() - startTime);
            DBDDataReceiver.startFetchWorkflow(dataReceiver, session, resultSet, firstRow, fetched);
            DBDDataReceiver.fetchRowsWithStatistics(dataReceiver, session, resultSet, statistics);
        }
        return statistics;
    }

    @NotNull
    private QueryResponse<Map<String, Object>> executeQuery(
        @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter,
        @NotNull List<SortBy> sortBy,
        int limit,
        int offset
    ) {
        WeaviateQueryClient<Map<String, Object>> query =
            handle(spec.getTenant()).query;
        switch (spec.getMode()) {
            case BM25:
                return query.bm25(WeaviateQueryRequest.requireQuery(spec, "BM25"),
                    b -> WeaviateQueryRequest.bm25Options(b, spec, filter, limit, offset));
            case NEAR_TEXT: {
                String text = WeaviateQueryRequest.requireQuery(spec, "Near Text");
                // A target is chosen by swapping the first argument, not by a builder call: the
                // Target record carries the query text as well as the vectors it applies to.
                NearText nearText = spec.hasTargets()
                    ? NearText.of(WeaviateQueryRequest.buildTextTarget(spec, text),
                        b -> WeaviateQueryRequest.nearTextOptions(b, spec, filter, limit, offset))
                    : NearText.of(text,
                        b -> WeaviateQueryRequest.nearTextOptions(b, spec, filter, limit, offset));
                return runNear(query, nearText, spec, () -> query.nearText(nearText));
            }
            case NEAR_VECTOR: {
                NearVector nearVector = spec.hasTargets()
                    ? NearVector.of(WeaviateQueryRequest.buildVectorTarget(spec),
                        b -> WeaviateQueryRequest.nearVectorOptions(b, spec, filter, limit, offset))
                    : NearVector.of(WeaviateQueryRequest.requireVector(spec),
                        b -> WeaviateQueryRequest.nearVectorOptions(b, spec, filter, limit, offset));
                return runNear(query, nearVector, spec, () -> query.nearVector(nearVector));
            }
            case NEAR_OBJECT: {
                NearObject nearObject = NearObject.of(WeaviateQueryRequest.requireObjectId(spec),
                    b -> WeaviateQueryRequest.nearObjectOptions(b, spec, filter, limit, offset));
                return runNear(query, nearObject, spec, () -> query.nearObject(nearObject));
            }
            case HYBRID: {
                String text = WeaviateQueryRequest.requireQuery(spec, "Hybrid");
                return spec.hasTargets()
                    ? query.hybrid(WeaviateQueryRequest.buildTextTarget(spec, text),
                        b -> WeaviateQueryRequest.hybridOptions(b, spec, filter, limit, offset))
                    : query.hybrid(text,
                        b -> WeaviateQueryRequest.hybridOptions(b, spec, filter, limit, offset));
            }
            case FETCH:
            default:
                return query.fetchObjects(b -> WeaviateQueryRequest.fetchOptions(b, spec, filter, sortBy, limit, offset));
        }
    }

    /**
     * The generative twin of {@link #executeQuery}: the same operators through the generate
     * client, which mirrors every overload with a trailing task argument. The two dispatchers
     * share the per-mode options methods below, so a query behaves identically with and without
     * a generative task attached.
     */
    @NotNull
    private GenerativeResponse<Map<String, Object>> executeGenerativeQuery(
        @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter,
        @NotNull List<SortBy> sortBy,
        int limit,
        int offset
    ) {
        WeaviateGenerateClient<Map<String, Object>> generate =
            handle(spec.getTenant()).generate;
        Function<GenerativeTask.Builder, ObjectBuilder<GenerativeTask>> task =
            t -> WeaviateQueryRequest.configureTask(t, spec.getGenerative());
        // The near_* arms need the task as an object, not a builder function: the score-reading
        // path constructs the request itself.
        GenerativeTask taskObject = GenerativeTask.of(task);
        switch (spec.getMode()) {
            case BM25:
                return generate.bm25(WeaviateQueryRequest.requireQuery(spec, "BM25"),
                    b -> WeaviateQueryRequest.bm25Options(b, spec, filter, limit, offset), task);
            case NEAR_TEXT: {
                String text = WeaviateQueryRequest.requireQuery(spec, "Near Text");
                NearText nearText = spec.hasTargets()
                    ? NearText.of(WeaviateQueryRequest.buildTextTarget(spec, text),
                        b -> WeaviateQueryRequest.nearTextOptions(b, spec, filter, limit, offset))
                    : NearText.of(text,
                        b -> WeaviateQueryRequest.nearTextOptions(b, spec, filter, limit, offset));
                return runNearGenerative(generate, nearText, taskObject, spec,
                    () -> generate.nearText(nearText, taskObject));
            }
            case NEAR_VECTOR: {
                NearVector nearVector = spec.hasTargets()
                    ? NearVector.of(WeaviateQueryRequest.buildVectorTarget(spec),
                        b -> WeaviateQueryRequest.nearVectorOptions(b, spec, filter, limit, offset))
                    : NearVector.of(WeaviateQueryRequest.requireVector(spec),
                        b -> WeaviateQueryRequest.nearVectorOptions(b, spec, filter, limit, offset));
                return runNearGenerative(generate, nearVector, taskObject, spec,
                    () -> generate.nearVector(nearVector, taskObject));
            }
            case NEAR_OBJECT: {
                NearObject nearObject = NearObject.of(WeaviateQueryRequest.requireObjectId(spec),
                    b -> WeaviateQueryRequest.nearObjectOptions(b, spec, filter, limit, offset));
                return runNearGenerative(generate, nearObject, taskObject, spec,
                    () -> generate.nearObject(nearObject, taskObject));
            }
            case HYBRID: {
                String text = WeaviateQueryRequest.requireQuery(spec, "Hybrid");
                return spec.hasTargets()
                    ? generate.hybrid(WeaviateQueryRequest.buildTextTarget(spec, text),
                        b -> WeaviateQueryRequest.hybridOptions(b, spec, filter, limit, offset), task)
                    : generate.hybrid(text,
                        b -> WeaviateQueryRequest.hybridOptions(b, spec, filter, limit, offset), task);
            }
            case FETCH:
            default:
                return generate.fetchObjects(
                    b -> WeaviateQueryRequest.fetchOptions(b, spec, filter, sortBy, limit, offset), task);
        }
    }

    /**
     * Build the rows of a grouped read.
     * <p>
     * A grouped reply gives both a flat object list and a map of groups. The flat list is what
     * the grid wants -- one row per object -- and each object names its own group, so the group's
     * numbers are joined back on per row. Grouping therefore needs no new result presentation:
     * it is the ordinary grid with a {@code _group} column.
     */
    private void appendGroupedRows(
        @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter,
        @NotNull List<SortBy> sortBy,
        int limit,
        int offset,
        @NotNull List<String> columnNames,
        @Nullable String defaultVectorName,
        @NotNull List<Object[]> rows
    ) {
        GroupBy groupBy = WeaviateQueryRequest.buildGroupBy(spec.getGroupBy());
        if (spec.getGenerative() != null) {
            GenerativeResponseGrouped<Map<String, Object>> response =
                executeGroupedGenerativeQuery(spec, filter, sortBy, limit, offset, groupBy);
            lastGenerativeGroupedResult =
                response.generative() == null ? null : response.generative().text();
            Map<String, GenerativeResponseGroup<Map<String, Object>>> groups = response.groups();
            for (QueryObjectGrouped<Map<String, Object>> obj : response.objects()) {
                GenerativeResponseGroup<Map<String, Object>> group =
                    groups == null ? null : groups.get(obj.belongsToGroup());
                // The generated text comes off the group, not the object: a grouped generative
                // reply has no per-object output, so every row of a group repeats its group's.
                rows.add(WeaviateRowMapper.toRow(columnNames, obj, defaultVectorName,
                    WeaviateRowMapper.GroupStats.of(group),
                    group == null ? null : group.generative(),
                    lastRerankScores.get(obj.uuid())));
            }
        } else {
            lastGenerativeGroupedResult = null;
            QueryResponseGrouped<Map<String, Object>> response =
                executeGroupedQuery(spec, filter, sortBy, limit, offset, groupBy);
            lastQueryProfile = WeaviateQueryProfile.from(response.queryProfile());
            Map<String, QueryResponseGroup<Map<String, Object>>> groups = response.groups();
            for (QueryObjectGrouped<Map<String, Object>> obj : response.objects()) {
                QueryResponseGroup<Map<String, Object>> group =
                    groups == null ? null : groups.get(obj.belongsToGroup());
                rows.add(WeaviateRowMapper.toRow(columnNames, obj, defaultVectorName,
                    WeaviateRowMapper.GroupStats.of(group), null,
                    lastRerankScores.get(obj.uuid())));
            }
        }
    }

    /**
     * The grouped twin of {@link #executeQuery}. Every operator has a trailing-{@code GroupBy}
     * overload, so this is the same dispatch with one more argument -- and, like the generative
     * twin, it shares the per-mode options methods rather than restating them.
     */
    @NotNull
    private QueryResponseGrouped<Map<String, Object>> executeGroupedQuery(
        @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter,
        @NotNull List<SortBy> sortBy,
        int limit,
        int offset,
        @NotNull GroupBy groupBy
    ) {
        WeaviateQueryClient<Map<String, Object>> query = handle(spec.getTenant()).query;
        switch (spec.getMode()) {
            case BM25:
                return query.bm25(WeaviateQueryRequest.requireQuery(spec, "BM25"),
                    b -> WeaviateQueryRequest.bm25Options(b, spec, filter, limit, offset), groupBy);
            case NEAR_TEXT: {
                String text = WeaviateQueryRequest.requireQuery(spec, "Near Text");
                NearText nearText = spec.hasTargets()
                    ? NearText.of(WeaviateQueryRequest.buildTextTarget(spec, text),
                        b -> WeaviateQueryRequest.nearTextOptions(b, spec, filter, limit, offset))
                    : NearText.of(text,
                        b -> WeaviateQueryRequest.nearTextOptions(b, spec, filter, limit, offset));
                return runNearGrouped(query, nearText, groupBy, spec,
                    () -> query.nearText(nearText, groupBy));
            }
            case NEAR_VECTOR: {
                NearVector nearVector = spec.hasTargets()
                    ? NearVector.of(WeaviateQueryRequest.buildVectorTarget(spec),
                        b -> WeaviateQueryRequest.nearVectorOptions(b, spec, filter, limit, offset))
                    : NearVector.of(WeaviateQueryRequest.requireVector(spec),
                        b -> WeaviateQueryRequest.nearVectorOptions(b, spec, filter, limit, offset));
                return runNearGrouped(query, nearVector, groupBy, spec,
                    () -> query.nearVector(nearVector, groupBy));
            }
            case NEAR_OBJECT: {
                NearObject nearObject = NearObject.of(WeaviateQueryRequest.requireObjectId(spec),
                    b -> WeaviateQueryRequest.nearObjectOptions(b, spec, filter, limit, offset));
                return runNearGrouped(query, nearObject, groupBy, spec,
                    () -> query.nearObject(nearObject, groupBy));
            }
            case HYBRID: {
                String text = WeaviateQueryRequest.requireQuery(spec, "Hybrid");
                return spec.hasTargets()
                    ? query.hybrid(WeaviateQueryRequest.buildTextTarget(spec, text),
                        b -> WeaviateQueryRequest.hybridOptions(b, spec, filter, limit, offset), groupBy)
                    : query.hybrid(text,
                        b -> WeaviateQueryRequest.hybridOptions(b, spec, filter, limit, offset), groupBy);
            }
            case FETCH:
            default:
                return query.fetchObjects(
                    b -> WeaviateQueryRequest.fetchOptions(b, spec, filter, sortBy, limit, offset), groupBy);
        }
    }

    /** {@link #executeGroupedQuery} through the generate client. */
    @NotNull
    private GenerativeResponseGrouped<Map<String, Object>> executeGroupedGenerativeQuery(
        @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter,
        @NotNull List<SortBy> sortBy,
        int limit,
        int offset,
        @NotNull GroupBy groupBy
    ) {
        WeaviateGenerateClient<Map<String, Object>> generate = handle(spec.getTenant()).generate;
        Function<GenerativeTask.Builder, ObjectBuilder<GenerativeTask>> task =
            t -> WeaviateQueryRequest.configureTask(t, spec.getGenerative());
        GenerativeTask taskObject = GenerativeTask.of(task);
        switch (spec.getMode()) {
            case BM25:
                return generate.bm25(WeaviateQueryRequest.requireQuery(spec, "BM25"),
                    b -> WeaviateQueryRequest.bm25Options(b, spec, filter, limit, offset), task, groupBy);
            case NEAR_TEXT: {
                String text = WeaviateQueryRequest.requireQuery(spec, "Near Text");
                NearText nearText = spec.hasTargets()
                    ? NearText.of(WeaviateQueryRequest.buildTextTarget(spec, text),
                        b -> WeaviateQueryRequest.nearTextOptions(b, spec, filter, limit, offset))
                    : NearText.of(text,
                        b -> WeaviateQueryRequest.nearTextOptions(b, spec, filter, limit, offset));
                return runNearGroupedGenerative(generate, nearText, taskObject, groupBy, spec,
                    () -> generate.nearText(nearText, taskObject, groupBy));
            }
            case NEAR_VECTOR: {
                NearVector nearVector = spec.hasTargets()
                    ? NearVector.of(WeaviateQueryRequest.buildVectorTarget(spec),
                        b -> WeaviateQueryRequest.nearVectorOptions(b, spec, filter, limit, offset))
                    : NearVector.of(WeaviateQueryRequest.requireVector(spec),
                        b -> WeaviateQueryRequest.nearVectorOptions(b, spec, filter, limit, offset));
                return runNearGroupedGenerative(generate, nearVector, taskObject, groupBy, spec,
                    () -> generate.nearVector(nearVector, taskObject, groupBy));
            }
            case NEAR_OBJECT: {
                NearObject nearObject = NearObject.of(WeaviateQueryRequest.requireObjectId(spec),
                    b -> WeaviateQueryRequest.nearObjectOptions(b, spec, filter, limit, offset));
                return runNearGroupedGenerative(generate, nearObject, taskObject, groupBy, spec,
                    () -> generate.nearObject(nearObject, taskObject, groupBy));
            }
            case HYBRID: {
                String text = WeaviateQueryRequest.requireQuery(spec, "Hybrid");
                // Task before options here, options before task everywhere else. That is the
                // client's own inconsistency, not a slip: the grouped generative hybrid overload
                // is declared (query, task, options, groupBy) while its ungrouped twin and every
                // other grouped operator take (query, options, task[, groupBy]). Both arguments
                // are Functions, so only their builder types keep this honest -- swap them and it
                // stops compiling rather than silently sending a hybrid with no alpha.
                return spec.hasTargets()
                    ? generate.hybrid(WeaviateQueryRequest.buildTextTarget(spec, text), task,
                        b -> WeaviateQueryRequest.hybridOptions(b, spec, filter, limit, offset), groupBy)
                    : generate.hybrid(text, task,
                        b -> WeaviateQueryRequest.hybridOptions(b, spec, filter, limit, offset), groupBy);
            }
            case FETCH:
            default:
                return generate.fetchObjects(
                    b -> WeaviateQueryRequest.fetchOptions(b, spec, filter, sortBy, limit, offset), task, groupBy);
        }
    }

    /** {@link #runNear} for a grouped search; same seam, the grouped response factory. */
    @NotNull
    private QueryResponseGrouped<Map<String, Object>> runNearGrouped(
        @NotNull WeaviateQueryClient<Map<String, Object>> query,
        @NotNull QueryOperator operator,
        @NotNull GroupBy groupBy,
        @NotNull WeaviateQuerySpec spec,
        @NotNull java.util.function.Supplier<QueryResponseGrouped<Map<String, Object>>> plain
    ) {
        if (spec.getRerank() == null || !WeaviateRerankSupport.isGroupedAvailable()) {
            return plain.get();
        }
        Map<String, Float> scores = new HashMap<>();
        QueryResponseGrouped<Map<String, Object>> response =
            WeaviateRerankSupport.searchGrouped(query, operator, groupBy, scores);
        if (response == null) {
            return plain.get();
        }
        lastRerankScores = scores;
        return response;
    }

    /** {@link #runNearGrouped} for a grouped generative search. */
    @NotNull
    private GenerativeResponseGrouped<Map<String, Object>> runNearGroupedGenerative(
        @NotNull WeaviateGenerateClient<Map<String, Object>> generate,
        @NotNull QueryOperator operator,
        @NotNull GenerativeTask task,
        @NotNull GroupBy groupBy,
        @NotNull WeaviateQuerySpec spec,
        @NotNull java.util.function.Supplier<GenerativeResponseGrouped<Map<String, Object>>> plain
    ) {
        if (spec.getRerank() == null || !WeaviateRerankSupport.isGroupedGenerativeAvailable()) {
            return plain.get();
        }
        Map<String, Float> scores = new HashMap<>();
        GenerativeResponseGrouped<Map<String, Object>> response =
            WeaviateRerankSupport.generateGrouped(generate, operator, task, groupBy, scores);
        if (response == null) {
            return plain.get();
        }
        lastRerankScores = scores;
        return response;
    }

    /**
     * Run a near_* search, reading the rerank score off the reply when the search was reranked.
     * <p>
     * The score is the one thing the typed client drops (see {@link WeaviateRerankSupport}), so
     * only a reranked query takes the observing path; everything else runs the plain call. If
     * the seam is unavailable the fallback runs too -- the query still works, the column is just
     * not offered, which is the same decision readData already made when it built the columns.
     */
    @NotNull
    private QueryResponse<Map<String, Object>> runNear(
        @NotNull WeaviateQueryClient<Map<String, Object>> query,
        @NotNull QueryOperator operator,
        @NotNull WeaviateQuerySpec spec,
        @NotNull java.util.function.Supplier<QueryResponse<Map<String, Object>>> plain
    ) {
        if (spec.getRerank() == null || !WeaviateRerankSupport.isAvailable()) {
            return plain.get();
        }
        Map<String, Float> scores = new HashMap<>();
        QueryResponse<Map<String, Object>> response =
            WeaviateRerankSupport.search(query, operator, scores);
        if (response == null) {
            return plain.get();
        }
        lastRerankScores = scores;
        return response;
    }

    /** {@link #runNear} for a generative search; same seam, different request type. */
    @NotNull
    private GenerativeResponse<Map<String, Object>> runNearGenerative(
        @NotNull WeaviateGenerateClient<Map<String, Object>> generate,
        @NotNull QueryOperator operator,
        @NotNull GenerativeTask task,
        @NotNull WeaviateQuerySpec spec,
        @NotNull java.util.function.Supplier<GenerativeResponse<Map<String, Object>>> plain
    ) {
        if (spec.getRerank() == null || !WeaviateRerankSupport.isGenerativeAvailable()) {
            return plain.get();
        }
        Map<String, Float> scores = new HashMap<>();
        GenerativeResponse<Map<String, Object>> response =
            WeaviateRerankSupport.generate(generate, operator, task, scores);
        if (response == null) {
            return plain.get();
        }
        lastRerankScores = scores;
        return response;
    }

    /**
     * How {@code propertyName}'s analyzer would split {@code text}.
     * <p>
     * Uses the per-property endpoint, so the tokenizer comes from the schema rather than from the
     * caller -- which is the question worth asking ("why did BM25 match this?") as opposed to
     * "what would tokenizer X do?".
     * <p>
     * Note the client's argument order: {@code forProperty(text, collection, property)}. The
     * parameter names are erased in the shipped jar, and the natural reading -- collection first,
     * as everywhere else in the client -- is wrong; it builds
     * {@code /v1/schema/{text}/properties/{collection}/tokenize} and fails on the first space in
     * the text. {@code WeaviateTokenizeLiveTest} pins this.
     *
     * @throws DBException when the property is not tokenized (the server answers 422 for a
     *                     non-text property) or the call fails
     */
    @NotNull
    public WeaviateTokenPreview previewTokenization(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String propertyName,
        @NotNull String text
    ) throws DBException {
        try {
            TokenizeResponse response =
                dataSource.getClient().tokenize.forProperty(text, getName(), propertyName);
            return new WeaviateTokenPreview(
                response.tokenization() == null ? null : response.tokenization().name(),
                response.indexed(),
                response.query());
        } catch (Exception e) {
            throw new DBException("Cannot tokenize " + getName() + "." + propertyName
                + ": " + e.getMessage(), e);
        }
    }

    @Override
    public long countData(
        @NotNull DBCExecutionSource source,
        @NotNull DBCSession session,
        @Nullable DBDDataFilter dataFilter,
        long flags
    ) throws DBException {
        WeaviateQuerySpec spec = getQuerySpec();
        if (spec.rankedResults()) {
            // BM25 / near-* / hybrid return a ranked, limit-bounded slice. There is no
            // meaningful "total matching rows" to aggregate over, and reporting the
            // unranked total would contradict what the grid shows. -1 means "unknown".
            return -1;
        }
        // Must mirror readData: both the column-header filter and the query-panel rows,
        // otherwise the count disagrees with the rows on screen.
        try {
            Filter columnHeaderFilter = WeaviateFilterTranslator.translate(dataFilter);
            Filter customFilter = WeaviateFilterTranslator.translateRows(spec.getFilterRows(), spec.isAnyFilter());
            Filter filter = WeaviateFilterTranslator.and(columnHeaderFilter, customFilter);
            AggregateResponse response = handle(spec.getTenant())
                .aggregate.overAll(b -> {
                    b.includeTotalCount(true);
                    if (filter != null) b.filters(filter);
                    return b;
                });
            Long total = response.totalCount();
            return total == null ? -1 : total;
        } catch (Exception e) {
            // -1 is this method's existing "unknown" signal. Throwing would discard the result
            // tab over a row count, and readData reports the same underlying failure anyway.
            queryLog.warn("Weaviate count failed for collection " + getName() + ": " + e.getMessage(), e);
            return -1;
        }
    }

    private static void populateColumns(
        @NotNull LocalResultSet<LocalStatement> rs,
        @NotNull List<WeaviateProperty> attributes,
        @NotNull List<String> vectorNames,
        boolean singleVector,
        @NotNull WeaviateQuerySpec spec
    ) {
        WeaviateQueryMode mode = spec.getMode();
        rs.addColumn(WeaviateColumns.UUID, DBPDataKind.STRING);
        for (WeaviateProperty p : attributes) {
            rs.addColumn(p.getName(), p.getDataKind());
        }
        for (String vectorName : vectorNames) {
            // Rendered as text - see WeaviateRowMapper#readVector.
            rs.addColumn(WeaviateColumns.vectorColumn(vectorName, singleVector), DBPDataKind.STRING);
        }
        // Must mirror the columnNames list built in readData, or the row mapper writes values
        // into the wrong columns.
        boolean groupedMetadata = spec.isGrouped();
        if (mode.hasScore() && !groupedMetadata) {
            rs.addColumn(WeaviateColumns.SCORE, DBPDataKind.NUMERIC);
        }
        if (mode.hasExplainScore() && spec.isExplainScore() && !groupedMetadata) {
            rs.addColumn(WeaviateColumns.EXPLAIN_SCORE, DBPDataKind.STRING);
        }
        if (mode.hasDistance()) {
            rs.addColumn(WeaviateColumns.DISTANCE, DBPDataKind.NUMERIC);
        }
        if (spec.isWithCertainty() && mode.supportsCertainty() && !groupedMetadata) {
            rs.addColumn(WeaviateColumns.CERTAINTY, DBPDataKind.NUMERIC);
        }
        if (WeaviateQueryRequest.hasRerankScore(spec)) {
            rs.addColumn(WeaviateColumns.RERANK_SCORE, DBPDataKind.NUMERIC);
        }
        if (spec.isGrouped()) {
            rs.addColumn(WeaviateColumns.GROUP, DBPDataKind.STRING);
            if (spec.getGroupBy().isWithGroupStats()) {
                rs.addColumn(WeaviateColumns.GROUP_COUNT, DBPDataKind.NUMERIC);
                rs.addColumn(WeaviateColumns.GROUP_MIN_DISTANCE, DBPDataKind.NUMERIC);
                rs.addColumn(WeaviateColumns.GROUP_MAX_DISTANCE, DBPDataKind.NUMERIC);
            }
        }
        if (spec.isWithCreated() && !spec.isGrouped()) {
            rs.addColumn(WeaviateColumns.CREATED, DBPDataKind.STRING);
        }
        if (spec.isWithUpdated() && !spec.isGrouped()) {
            rs.addColumn(WeaviateColumns.UPDATED, DBPDataKind.STRING);
        }
        if (spec.getGenerative() != null && spec.getGenerative().getSinglePrompt() != null) {
            rs.addColumn(WeaviateColumns.GENERATED, DBPDataKind.STRING);
            if (spec.getGenerative().isReturnMetadata()) {
                rs.addColumn(WeaviateColumns.GENERATIVE_META, DBPDataKind.STRING);
            }
        }
    }

    /**
     * Names of the vectors declared by this collection, sorted for stable column ordering.
     */
    @NotNull
    private List<String> getVectorNames() {
        Map<String, VectorConfig> vectors = config.vectors();
        if (vectors == null || vectors.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(new TreeMap<>(vectors).keySet());
    }

    /**
     * Query settings held on the data source, so they survive collection-cache reloads
     * (this instance is replaced whenever the navigator refreshes).
     */
    @NotNull
    public WeaviateQuerySpec getQuerySpec() {
        return dataSource.getQuerySpec(getName());
    }

    public void setQuerySpec(@Nullable WeaviateQuerySpec spec) {
        dataSource.setQuerySpec(getName(), spec);
    }

    /**
     * Allow the next reads to execute the remembered spec in full. The Query panel calls this
     * from Run; without it, {@link #readData} demotes an expensive spec to a plain fetch -- see
     * {@link WeaviateQuerySpec#isAutoRunSafe()}.
     */
    public void armRun() {
        dataSource.armRun(getName());
    }

    /**
     * A freshly opened viewer calls this before its first read, so a search or generative task
     * remembered from the last session does not re-fire just because the tab was reopened.
     */
    public void disarmRun() {
        dataSource.disarmRun(getName());
    }

    /**
     * Re-reads this collection's definition from the server and drops everything derived from it.
     */
    public void refreshConfig() throws DBException {
        try {
            CollectionConfig fresh = dataSource.getClient().collections.getConfig(getName())
                .orElseThrow(() -> new DBException(getName() + " no longer exists"));
            this.config = fresh;
            this.attributes = null;
            this.definitionNodes = null;
            resetTenantCache();
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBException("Cannot re-read " + getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Re-reads this collection when the navigator refreshes its node.
     * <p>
     * Without this the collection is not a {@link DBPRefreshableObject}, and
     * {@code DBNDatabaseNode#refreshNode} falls through to {@code DBNNode#refreshNode}, which
     * hands the request to the parent -- recursively, all the way up to the datasource node. That
     * disconnects and reconnects the connection, so refreshing one collection tore down the
     * client and every later call failed with a null {@code getClient()} until the editor was
     * reopened.
     *
     * @return this collection, since Weaviate has nothing to replace it with -- the definition is
     *         re-read in place
     */
    @Nullable
    @Override
    public DBSObject refreshObject(@NotNull DBRProgressMonitor monitor) throws DBException {
        refreshConfig();
        return this;
    }

    // ---- Tenants -----------------------------------------------------------------------------
    //
    // Thin delegates over WeaviateTenancy, which owns the tenant node cache. They stay here
    // because the navigator reflects over this class (getTenantNodes carries @Association) and
    // because 26 call sites across the UI and test bundles reach tenants through the collection.

    /**
     * True when the collection partitions its objects by tenant.
     * <p>
     * Weaviate rejects a query against such a collection unless it names a tenant, so the data
     * view has to ask which one before it can show anything.
     */
    public boolean isMultiTenant() {
        return tenancy.isMultiTenant();
    }

    /**
     * Whether writing to an unknown tenant creates it, or null when the server never said.
     * <p>
     * Null is not the same as false -- see {@link WeaviateTenancy#getAutoTenantCreation()}.
     */
    @Nullable
    public Boolean getAutoTenantCreation() {
        return tenancy.getAutoTenantCreation();
    }

    /** Whether reading an inactive tenant wakes it. See {@link #getAutoTenantCreation()} on null. */
    @Nullable
    public Boolean getAutoTenantActivation() {
        return tenancy.getAutoTenantActivation();
    }

    /** Turns automatic tenant creation and activation on or off. */
    public void setAutoTenantOptions(
        @NotNull DBRProgressMonitor monitor,
        boolean autoCreation,
        boolean autoActivation
    ) throws DBException {
        tenancy.setAutoTenantOptions(monitor, autoCreation, autoActivation);
    }

    /** Tenants defined for this collection with their states, ordered by name. */
    @NotNull
    public List<WeaviateTenant> listTenants(@NotNull DBRProgressMonitor monitor) throws DBException {
        return tenancy.listTenants(monitor);
    }

    /** Tenants as navigator nodes, for the Tenants folder under a multi-tenant collection. */
    @Association
    public List<WeaviateTenantNode> getTenantNodes(@NotNull DBRProgressMonitor monitor) throws DBException {
        return tenancy.getTenantNodes(monitor);
    }

    /** Tenant nodes already in memory, or null if this collection's tenants have not been read. */
    @Nullable
    public List<WeaviateTenantNode> getLoadedTenantNodes() {
        return tenancy.getLoadedTenantNodes();
    }

    /** Forgets the cached tenant nodes. Called after any change of state. */
    public void resetTenantCache() {
        tenancy.resetTenantCache();
    }

    /** Activates or deactivates the named tenants, and reports how many actually changed. */
    public int setTenantStatus(
        @NotNull DBRProgressMonitor monitor,
        @NotNull List<WeaviateTenant> tenants,
        @NotNull WeaviateTenantStatus target
    ) throws DBException {
        return tenancy.setTenantStatus(monitor, tenants, target);
    }

    /**
     * Turns a query failure into something the user can act on.
     * <p>
     * Two problems with the raw text. The message that reaches the top is the paginator's
     * ("fetch next page, page_size=100 cursor=null"), which describes the call rather than the
     * failure -- the reason is always one or more causes down. And the most common reason on a
     * multi-tenant collection is a tenant that is simply switched off, which the server reports
     * as "tenant not active" wrapped in gRPC framing.
     * <p>
     * So the chain is walked: if anything in it says the tenant is inactive, the answer says so
     * and where to fix it. Otherwise the deepest message wins, because that is the one describing
     * what actually went wrong.
     */
    @NotNull
    private String describeFailure(@NotNull Throwable failure, @Nullable String tenant) {
        String deepest = null;
        boolean tenantInactive = false;
        for (Throwable t = failure; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (!CommonUtils.isEmpty(message)) {
                deepest = message;
                if (message.toLowerCase(java.util.Locale.ROOT).contains("tenant not active")) {
                    tenantInactive = true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        if (tenantInactive) {
            String name = CommonUtils.isEmpty(tenant) ? "The selected tenant" : "Tenant \"" + tenant + "\"";
            return name + " is inactive, so Weaviate refuses to read it. Right-click "
                + getName() + " and choose \"Manage Tenants...\" to activate it.";
        }
        if (deepest == null) {
            deepest = failure.getClass().getSimpleName();
        }
        return "Query failed: " + deepest;
    }

    /**
     * Collection handle scoped to {@code tenant} when one is given.
     * <p>
     * Every request has to go through here: a handle built without the tenant silently targets
     * nothing on a multi-tenant collection, so reads look empty and deletes appear to do nothing.
     */
    @NotNull
    CollectionHandle<Map<String, Object>> handle(@Nullable String tenant) {
        if (tenant == null || tenant.isBlank()) {
            return dataSource.getClient().collections.use(getName());
        }
        return dataSource.getClient().collections.use(getName(), b -> b.tenant(tenant));
    }

    /**
     * Whether this read should carry embeddings.
     * <p>
     * An explicit choice in the Query panel always wins. Without one, the answer depends on who
     * is reading: the grid gets no vectors, because an embedding is hundreds of columns wide and
     * pushes the real properties off screen, while a non-interactive read -- an export -- honours
     * the connection setting, since that is exactly where embeddings are wanted.
     */
    private boolean includeVectors(@NotNull DBCSession session, @NotNull WeaviateQuerySpec spec) {
        if (dataSource.hasQuerySpec(getName())) {
            return spec.isIncludeVector();
        }
        if (session.getPurpose().isUser()) {
            return false;
        }
        return dataSource.isIncludeVectorsByDefault();
    }

    // ---- DBSDataManipulator ----------------------------------------------------------------

    @NotNull
    @Override
    public ExecuteBatch deleteData(
        @NotNull DBCSession session,
        @NotNull DBSAttributeBase[] keyAttributes,
        @NotNull DBCExecutionSource source
    ) throws DBException {
        int uuidIndex = -1;
        for (int i = 0; i < keyAttributes.length; i++) {
            if (WeaviateColumns.UUID.equalsIgnoreCase(keyAttributes[i].getName())) {
                uuidIndex = i;
                break;
            }
        }
        if (uuidIndex < 0) {
            // Only reachable if the row identifier resolved to something other than the uuid
            // pseudo-attribute; deleting on any other basis would target the wrong objects.
            throw new DBException(
                "Weaviate rows can only be deleted by their " + WeaviateColumns.UUID
                    + ". Make sure that column is present in the result set.");
        }
        String tenant = getQuerySpec().getTenant();
        if (isMultiTenant() && CommonUtils.isEmpty(tenant)) {
            // Without a tenant the request would match nothing and report a cheerful zero.
            throw new DBException(
                "Select a tenant in the Weaviate Query panel before deleting from a multi-tenant collection");
        }
        return new WeaviateDeleteBatch(this, uuidIndex, tenant);
    }

    @NotNull
    @Override
    public ExecuteBatch insertData(
        @NotNull DBCSession session,
        @NotNull DBSAttributeBase[] attributes,
        @Nullable DBDDataReceiver keysReceiver,
        @NotNull DBCExecutionSource source,
        @NotNull Map<String, Object> options
    ) throws DBException {
        throw new DBException("Adding objects from the data grid is not supported yet");
    }

    @NotNull
    @Override
    public ExecuteBatch updateData(
        @NotNull DBCSession session,
        @NotNull DBSAttributeBase[] updateAttributes,
        @NotNull DBSAttributeBase[] keyAttributes,
        @Nullable DBDDataReceiver keysReceiver,
        @NotNull DBCExecutionSource source
    ) throws DBException {
        throw new DBException("Editing objects from the data grid is not supported yet");
    }

    @NotNull
    @Override
    public DBCStatistics truncateData(
        @NotNull DBCSession session,
        @NotNull DBCExecutionSource source
    ) throws DBException {
        throw new DBException("Truncating a Weaviate collection is not supported yet");
    }

    @Nullable
    public String getLastQueryError() {
        return lastQueryError;
    }

    public void clearLastQueryError() {
        this.lastQueryError = null;
    }

    /** The last read's query profile, or null when none was requested or none came back. */
    @Nullable
    public WeaviateQueryProfile getLastQueryProfile() {
        return lastQueryProfile;
    }

    @Nullable
    public String getLastGenerativeGroupedResult() {
        return lastGenerativeGroupedResult;
    }
}
