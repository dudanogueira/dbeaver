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
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataSource;
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

public class WeaviateCollection implements DBSEntity, DBSDataManipulator {

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
    /**
     * Beyond this many tenants the platform's choice dialog (a flat list of labels) stops being
     * usable, and the searchable "Select Tenant..." picker is the only sensible way in.
     */
    private static final int MAX_TENANTS_TO_PROMPT = 30;

    private static final String TENANT_REQUIRED =
        "This collection is multi-tenant. Right-click the collection and choose "
            + "\"Select Tenant...\" to pick one.";

    private WeaviateUuidAttribute uuidAttribute;
    private WeaviateUuidConstraint uuidConstraint;

    private volatile String lastQueryError;
    /** Navigator nodes for the Tenants folder; dropped whenever a tenant's state changes. */
    private volatile List<WeaviateTenantNode> tenantNodes;
    /**
     * The last grouped-task generative output, or null. One text for the whole result set, so
     * it has no row to live on -- the Query panel shows it in the Generative section, polling
     * this after each run exactly as it polls {@link #lastQueryError}.
     */
    private volatile String lastGenerativeGroupedResult;
    /**
     * Rerank scores of the last read, by uuid. Filled only by a reranked near_* search; the row
     * mapper reads it because the score reaches us outside the typed object.
     */
    private volatile Map<String, Float> lastRerankScores = Collections.emptyMap();
    private volatile String pendingSchemaJson;
    private volatile List<WeaviateJsonNode> definitionNodes;

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

    @Association
    public List<WeaviateMetadataField> getReplicationFields(@NotNull DBRProgressMonitor monitor) {
        return WeaviateRecordIntrospect.toFields(this, config.replication());
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

    @NotNull
    private List<WeaviateJsonNode> loadDefinitionNodes() throws DBException {
        if (!persisted) {
            return Collections.emptyList();
        }
        String rawJson;
        if (dataSource.isRestApiAvailable()) {
            rawJson = WeaviateSchemaRest.fetchCollectionSchema(dataSource, getName());
        } else {
            // OIDC connections cannot authenticate a direct REST call, so fall back to the client's
            // own view of the definition. It is narrower - fields the bundled client cannot model
            // are absent - but showing what we can beats showing nothing.
            queryLog.debug("Direct REST unavailable on this connection; showing the definition of '"
                + getName() + "' as understood by the client");
            rawJson = toSchemaJson();
        }
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
            ? narrowToTargets(spec, declaredVectors)
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
        if (hasRerankScore(exec)) {
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
            String chosen = promptForTenant(session, monitor, spec);
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
        List<SortBy> sortBy = spec.rankedResults() ? Collections.emptyList() : buildSortBy(dataFilter, attributes);
        int limit = maxRows > 0 ? (int) Math.min(maxRows, Integer.MAX_VALUE) : 0;
        int offset = firstRow > 0 ? (int) Math.min(firstRow, Integer.MAX_VALUE) : 0;

        String queryText = describeQuery(exec, filter, sortBy, limit, offset);
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
            if (exec.isGrouped()) {
                appendGroupedRows(exec, filter, sortBy, limit, offset,
                    columnNames, defaultVectorName, rows);
            } else if (exec.getGenerative() != null) {
                GenerativeResponse<Map<String, Object>> response =
                    executeGenerativeQuery(exec, filter, sortBy, limit, offset);
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
                        List<Metadata> extra = extraMetadata(spec);
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
                return query.bm25(requireQuery(spec, "BM25"),
                    b -> bm25Options(b, spec, filter, limit, offset));
            case NEAR_TEXT: {
                String text = requireQuery(spec, "Near Text");
                // A target is chosen by swapping the first argument, not by a builder call: the
                // Target record carries the query text as well as the vectors it applies to.
                NearText nearText = spec.hasTargets()
                    ? NearText.of(buildTextTarget(spec, text),
                        b -> nearTextOptions(b, spec, filter, limit, offset))
                    : NearText.of(text,
                        b -> nearTextOptions(b, spec, filter, limit, offset));
                return runNear(query, nearText, spec, () -> query.nearText(nearText));
            }
            case NEAR_VECTOR: {
                NearVector nearVector = spec.hasTargets()
                    ? NearVector.of(buildVectorTarget(spec),
                        b -> nearVectorOptions(b, spec, filter, limit, offset))
                    : NearVector.of(requireVector(spec),
                        b -> nearVectorOptions(b, spec, filter, limit, offset));
                return runNear(query, nearVector, spec, () -> query.nearVector(nearVector));
            }
            case NEAR_OBJECT: {
                NearObject nearObject = NearObject.of(requireObjectId(spec),
                    b -> nearObjectOptions(b, spec, filter, limit, offset));
                return runNear(query, nearObject, spec, () -> query.nearObject(nearObject));
            }
            case HYBRID: {
                String text = requireQuery(spec, "Hybrid");
                return spec.hasTargets()
                    ? query.hybrid(buildTextTarget(spec, text),
                        b -> hybridOptions(b, spec, filter, limit, offset))
                    : query.hybrid(text,
                        b -> hybridOptions(b, spec, filter, limit, offset));
            }
            case FETCH:
            default:
                return query.fetchObjects(b -> fetchOptions(b, spec, filter, sortBy, limit, offset));
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
            t -> configureTask(t, spec.getGenerative());
        // The near_* arms need the task as an object, not a builder function: the score-reading
        // path constructs the request itself.
        GenerativeTask taskObject = GenerativeTask.of(task);
        switch (spec.getMode()) {
            case BM25:
                return generate.bm25(requireQuery(spec, "BM25"),
                    b -> bm25Options(b, spec, filter, limit, offset), task);
            case NEAR_TEXT: {
                String text = requireQuery(spec, "Near Text");
                NearText nearText = spec.hasTargets()
                    ? NearText.of(buildTextTarget(spec, text),
                        b -> nearTextOptions(b, spec, filter, limit, offset))
                    : NearText.of(text,
                        b -> nearTextOptions(b, spec, filter, limit, offset));
                return runNearGenerative(generate, nearText, taskObject, spec,
                    () -> generate.nearText(nearText, taskObject));
            }
            case NEAR_VECTOR: {
                NearVector nearVector = spec.hasTargets()
                    ? NearVector.of(buildVectorTarget(spec),
                        b -> nearVectorOptions(b, spec, filter, limit, offset))
                    : NearVector.of(requireVector(spec),
                        b -> nearVectorOptions(b, spec, filter, limit, offset));
                return runNearGenerative(generate, nearVector, taskObject, spec,
                    () -> generate.nearVector(nearVector, taskObject));
            }
            case NEAR_OBJECT: {
                NearObject nearObject = NearObject.of(requireObjectId(spec),
                    b -> nearObjectOptions(b, spec, filter, limit, offset));
                return runNearGenerative(generate, nearObject, taskObject, spec,
                    () -> generate.nearObject(nearObject, taskObject));
            }
            case HYBRID: {
                String text = requireQuery(spec, "Hybrid");
                return spec.hasTargets()
                    ? generate.hybrid(buildTextTarget(spec, text),
                        b -> hybridOptions(b, spec, filter, limit, offset), task)
                    : generate.hybrid(text,
                        b -> hybridOptions(b, spec, filter, limit, offset), task);
            }
            case FETCH:
            default:
                return generate.fetchObjects(
                    b -> fetchOptions(b, spec, filter, sortBy, limit, offset), task);
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
        GroupBy groupBy = buildGroupBy(spec.getGroupBy());
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

    @NotNull
    private static GroupBy buildGroupBy(@NotNull WeaviateGroupBySpec spec) {
        return GroupBy.property(
            spec.getProperty(), spec.getMaxGroups(), spec.getMaxObjectsPerGroup());
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
                return query.bm25(requireQuery(spec, "BM25"),
                    b -> bm25Options(b, spec, filter, limit, offset), groupBy);
            case NEAR_TEXT: {
                String text = requireQuery(spec, "Near Text");
                NearText nearText = spec.hasTargets()
                    ? NearText.of(buildTextTarget(spec, text),
                        b -> nearTextOptions(b, spec, filter, limit, offset))
                    : NearText.of(text,
                        b -> nearTextOptions(b, spec, filter, limit, offset));
                return runNearGrouped(query, nearText, groupBy, spec,
                    () -> query.nearText(nearText, groupBy));
            }
            case NEAR_VECTOR: {
                NearVector nearVector = spec.hasTargets()
                    ? NearVector.of(buildVectorTarget(spec),
                        b -> nearVectorOptions(b, spec, filter, limit, offset))
                    : NearVector.of(requireVector(spec),
                        b -> nearVectorOptions(b, spec, filter, limit, offset));
                return runNearGrouped(query, nearVector, groupBy, spec,
                    () -> query.nearVector(nearVector, groupBy));
            }
            case NEAR_OBJECT: {
                NearObject nearObject = NearObject.of(requireObjectId(spec),
                    b -> nearObjectOptions(b, spec, filter, limit, offset));
                return runNearGrouped(query, nearObject, groupBy, spec,
                    () -> query.nearObject(nearObject, groupBy));
            }
            case HYBRID: {
                String text = requireQuery(spec, "Hybrid");
                return spec.hasTargets()
                    ? query.hybrid(buildTextTarget(spec, text),
                        b -> hybridOptions(b, spec, filter, limit, offset), groupBy)
                    : query.hybrid(text,
                        b -> hybridOptions(b, spec, filter, limit, offset), groupBy);
            }
            case FETCH:
            default:
                return query.fetchObjects(
                    b -> fetchOptions(b, spec, filter, sortBy, limit, offset), groupBy);
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
            t -> configureTask(t, spec.getGenerative());
        GenerativeTask taskObject = GenerativeTask.of(task);
        switch (spec.getMode()) {
            case BM25:
                return generate.bm25(requireQuery(spec, "BM25"),
                    b -> bm25Options(b, spec, filter, limit, offset), task, groupBy);
            case NEAR_TEXT: {
                String text = requireQuery(spec, "Near Text");
                NearText nearText = spec.hasTargets()
                    ? NearText.of(buildTextTarget(spec, text),
                        b -> nearTextOptions(b, spec, filter, limit, offset))
                    : NearText.of(text,
                        b -> nearTextOptions(b, spec, filter, limit, offset));
                return runNearGroupedGenerative(generate, nearText, taskObject, groupBy, spec,
                    () -> generate.nearText(nearText, taskObject, groupBy));
            }
            case NEAR_VECTOR: {
                NearVector nearVector = spec.hasTargets()
                    ? NearVector.of(buildVectorTarget(spec),
                        b -> nearVectorOptions(b, spec, filter, limit, offset))
                    : NearVector.of(requireVector(spec),
                        b -> nearVectorOptions(b, spec, filter, limit, offset));
                return runNearGroupedGenerative(generate, nearVector, taskObject, groupBy, spec,
                    () -> generate.nearVector(nearVector, taskObject, groupBy));
            }
            case NEAR_OBJECT: {
                NearObject nearObject = NearObject.of(requireObjectId(spec),
                    b -> nearObjectOptions(b, spec, filter, limit, offset));
                return runNearGroupedGenerative(generate, nearObject, taskObject, groupBy, spec,
                    () -> generate.nearObject(nearObject, taskObject, groupBy));
            }
            case HYBRID: {
                String text = requireQuery(spec, "Hybrid");
                // Task before options here, options before task everywhere else. That is the
                // client's own inconsistency, not a slip: the grouped generative hybrid overload
                // is declared (query, task, options, groupBy) while its ungrouped twin and every
                // other grouped operator take (query, options, task[, groupBy]). Both arguments
                // are Functions, so only their builder types keep this honest -- swap them and it
                // stops compiling rather than silently sending a hybrid with no alpha.
                return spec.hasTargets()
                    ? generate.hybrid(buildTextTarget(spec, text), task,
                        b -> hybridOptions(b, spec, filter, limit, offset), groupBy)
                    : generate.hybrid(text, task,
                        b -> hybridOptions(b, spec, filter, limit, offset), groupBy);
            }
            case FETCH:
            default:
                return generate.fetchObjects(
                    b -> fetchOptions(b, spec, filter, sortBy, limit, offset), task, groupBy);
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

    // One options method per mode, shared verbatim by the plain and generative dispatchers.
    // Concrete builder types throughout: the ancestors carrying the shared setters are
    // package-private in the client, so there is no common type to abstract over.

    private static Bm25.Builder bm25Options(
        @NotNull Bm25.Builder b, @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter, int limit, int offset
    ) {
        applyCommon(b, spec, filter, limit, offset);
        b.returnMetadata(scoreMetadata(spec));
        List<String> queryProperties = spec.getQueryProperties();
        if (!queryProperties.isEmpty()) b.queryProperties(queryProperties);
        return b;
    }

    private static NearText.Builder nearTextOptions(
        @NotNull NearText.Builder b, @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter, int limit, int offset
    ) {
        applyCommon(b, spec, filter, limit, offset);
        Rerank rerank = buildRerank(spec);
        if (rerank != null) b.rerank(rerank);
        b.returnMetadata(Metadata.DISTANCE);
        if (spec.getDistance() != null) b.distance(spec.getDistance());
        return b;
    }

    private static NearVector.Builder nearVectorOptions(
        @NotNull NearVector.Builder b, @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter, int limit, int offset
    ) {
        applyCommon(b, spec, filter, limit, offset);
        Rerank rerank = buildRerank(spec);
        if (rerank != null) b.rerank(rerank);
        b.returnMetadata(Metadata.DISTANCE);
        if (spec.getDistance() != null) b.distance(spec.getDistance());
        return b;
    }

    private static NearObject.Builder nearObjectOptions(
        @NotNull NearObject.Builder b, @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter, int limit, int offset
    ) {
        applyCommon(b, spec, filter, limit, offset);
        Rerank rerank = buildRerank(spec);
        if (rerank != null) b.rerank(rerank);
        b.returnMetadata(Metadata.DISTANCE);
        if (spec.getDistance() != null) b.distance(spec.getDistance());
        return b;
    }

    private static Hybrid.Builder hybridOptions(
        @NotNull Hybrid.Builder b, @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter, int limit, int offset
    ) {
        applyCommon(b, spec, filter, limit, offset);
        b.returnMetadata(scoreMetadata(spec));
        if (spec.getAlpha() != null) b.alpha(spec.getAlpha());
        if (spec.getFusionType() != null) b.fusionType(spec.getFusionType().toClientType());
        return b;
    }

    private static FetchObjects.Builder fetchOptions(
        @NotNull FetchObjects.Builder b, @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter, @NotNull List<SortBy> sortBy, int limit, int offset
    ) {
        applyCommon(b, spec, filter, limit, offset);
        if (!sortBy.isEmpty()) b.sort(sortBy);
        return b;
    }

    @NotNull
    private static float[] requireVector(@NotNull WeaviateQuerySpec spec) {
        float[] vector = spec.getVector();
        if (vector == null || vector.length == 0) {
            throw new IllegalStateException("Near Vector mode requires a non-empty vector");
        }
        return vector;
    }

    /**
     * Near Vector is handed a vector; Near Object is handed an object id and the server
     * resolves that object's stored vector itself, so this works even when the reference
     * object's vector is never returned to the client.
     */
    @NotNull
    private static String requireObjectId(@NotNull WeaviateQuerySpec spec) {
        String uuid = spec.getObjectId();
        if (uuid == null || uuid.isBlank()) {
            throw new IllegalStateException(
                "Near Object mode requires the UUID of a reference object");
        }
        return uuid;
    }

    /**
     * The spec's generative task as the client wants it. The provider override only rides along
     * when one was chosen -- with none sent, the server falls back to the collection's own
     * generative module, which is the ordinary case.
     */
    @NotNull
    private static ObjectBuilder<GenerativeTask> configureTask(
        @NotNull GenerativeTask.Builder t,
        @NotNull WeaviateGenerativeTask spec
    ) {
        GenerativeProvider provider = spec.getProvider() == null ? null
            : spec.getProvider().toClientProvider(
                spec.getModel(), spec.getTemperature(), spec.getMaxTokens());
        if (spec.getSinglePrompt() != null) {
            t.singlePrompt(spec.getSinglePrompt(), sb -> {
                if (spec.isReturnMetadata()) sb.metadata(true);
                if (provider != null) sb.generativeProvider(provider);
                return sb;
            });
        }
        if (spec.getGroupedTask() != null) {
            t.groupedTask(spec.getGroupedTask(), gb -> {
                if (!spec.getGroupedProperties().isEmpty()) {
                    gb.properties(spec.getGroupedProperties());
                }
                if (spec.isReturnMetadata()) gb.metadata(true);
                if (provider != null) gb.generativeProvider(provider);
                return gb;
            });
        }
        return t;
    }

    /**
     * The Target for a text-driven search -- Near Text or Hybrid -- carrying both the query text
     * and the named vectors to embed it against.
     * <p>
     * A single target sends no join strategy: there is nothing to join, and the client's combined
     * record would insist on one anyway.
     */
    @NotNull
    private static Target buildTextTarget(@NotNull WeaviateQuerySpec spec, @NotNull String text) {
        List<WeaviateVectorTarget> targets = spec.getTargets();
        List<String> queries = List.of(text);
        if (targets.size() == 1) {
            return new Target.TextTarget(weightOf(targets.get(0)), queries);
        }
        List<Target.VectorWeight> weights = new ArrayList<>(targets.size());
        for (WeaviateVectorTarget target : targets) {
            weights.add(weightOf(target));
        }
        return new Target.CombinedTextTarget(queries, combinationOf(spec), weights);
    }

    /**
     * The Target for Near Vector, where each named vector is searched with its own query vector.
     * Different vector spaces have different shapes, so there is no one vector to share.
     */
    @NotNull
    private static NearVectorTarget buildVectorTarget(@NotNull WeaviateQuerySpec spec) {
        List<WeaviateVectorTarget> targets = spec.getTargets();
        List<Target.VectorTarget> vectorTargets = new ArrayList<>(targets.size());
        for (WeaviateVectorTarget target : targets) {
            Object vector = target.queryVectorForClient();
            if (vector == null) {
                throw new IllegalStateException(
                    "Near Vector target " + target.getName() + " has no query vector");
            }
            vectorTargets.add(new Target.VectorTarget(target.getName(), target.getWeight(), vector));
        }
        if (vectorTargets.size() == 1) {
            return vectorTargets.get(0);
        }
        return new Target.CombinedVectorTarget(combinationOf(spec), vectorTargets);
    }

    @NotNull
    private static Target.VectorWeight weightOf(@NotNull WeaviateVectorTarget target) {
        return new Target.VectorWeight(target.getName(), target.getWeight());
    }

    /**
     * The spec's rerank request as the client type, or null for none. Attached inline in the
     * near_* dispatch arms -- only their builders expose rerank in client 6.3.1 (see
     * {@link WeaviateQueryMode#supportsRerank()}), and their common ancestor carrying the
     * setter is package-private, so there is no type to write a shared helper against.
     */
    @Nullable
    private static Rerank buildRerank(@NotNull WeaviateQuerySpec spec) {
        WeaviateRerankSpec rerank = spec.getRerank();
        if (rerank == null) {
            return null;
        }
        String query = rerank.getQuery();
        return query == null
            ? Rerank.by(rerank.getProperty())
            : Rerank.by(rerank.getProperty(), rb -> rb.query(query));
    }

    /**
     * The join strategy to send for a multi-target search. Defaults to MIN, which is what Weaviate
     * itself falls back to -- the client's combined records require a strategy, so there is no way
     * to send "unspecified" and let the server decide.
     */
    @NotNull
    private static Target.CombinationMethod combinationOf(@NotNull WeaviateQuerySpec spec) {
        WeaviateVectorCombination combination = spec.getCombination();
        return combination == null
            ? WeaviateVectorCombination.MIN.toClientType()
            : combination.toClientType();
    }

    private static <B extends io.weaviate.client6.v1.api.collections.query.BaseQueryOptions.Builder<B, ?>>
    void applyCommon(
        @NotNull B b,
        @NotNull WeaviateQuerySpec spec,
        @Nullable Filter filter,
        int limit,
        int offset
    ) {
        if (limit > 0) b.limit(limit);
        if (offset > 0) b.offset(offset);
        if (filter != null) b.filters(filter);
        if (spec.isIncludeVector()) {
            // Ask for only the targeted vectors when the search names any, so the grid columns
            // built from the same list in readData are the ones that actually come back.
            List<String> requested = targetVectorNames(spec);
            if (requested.isEmpty()) {
                b.includeVector();
            } else {
                b.includeVector(requested);
            }
        }
        // The client calls Weaviate's autocut "autolimit"; the wire field is autocut.
        Integer autoCut = spec.getAutoCut();
        if (autoCut != null && autoCut > 0 && spec.getMode().supportsAutoCut()) {
            b.autolimit(autoCut);
        }
        // Opt-in metadata. returnMetadata is additive across calls on the same builder, so this
        // does not disturb the per-mode SCORE/DISTANCE requests made at the dispatch sites.
        List<Metadata> extra = extraMetadata(spec);
        if (!extra.isEmpty()) {
            b.returnMetadata(extra);
        }
    }

    /**
     * The metadata the user opted into beyond what the mode itself needs, ready to request.
     * Certainty is gated on the mode: the server derives it from vector distance, so asking for
     * it elsewhere returns nothing and the flag is simply ignored.
     */
    @NotNull
    private static List<Metadata> extraMetadata(@NotNull WeaviateQuerySpec spec) {
        List<Metadata> extra = new ArrayList<>(3);
        if (spec.isWithCreated()) extra.add(Metadata.CREATION_TIME_UNIX);
        if (spec.isWithUpdated()) extra.add(Metadata.LAST_UPDATE_TIME_UNIX);
        if (spec.isWithCertainty() && spec.getMode().supportsCertainty()) {
            extra.add(Metadata.CERTAINTY);
        }
        return extra;
    }

    /**
     * Whether a rerank score column is offered for this spec.
     * <p>
     * Everything here is knowable before the query runs, which is what lets the column be
     * offered only when it will actually be filled: the search must be reranked, the seam that
     * reads the score must have resolved, and the read must not be generative -- the generate
     * client uses its own Rpc, which this does not wrap.
     */
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

    private static boolean hasRerankScore(@NotNull WeaviateQuerySpec spec) {
        if (spec.getRerank() == null) {
            return false;
        }
        // Each path has its own seam, and any can resolve without the others. Grouped is a
        // separate seam again: a grouped reply carries its objects somewhere else entirely, so
        // reading a score off one is not the same operation as reading it off a flat reply.
        if (spec.isGrouped()) {
            return spec.getGenerative() != null
                ? WeaviateRerankSupport.isGroupedGenerativeAvailable()
                : WeaviateRerankSupport.isGroupedAvailable();
        }
        return spec.getGenerative() != null
            ? WeaviateRerankSupport.isGenerativeAvailable()
            : WeaviateRerankSupport.isAvailable();
    }

    /**
     * The declared vectors a query is about: its targets when it names any, all of them otherwise.
     * A target the collection no longer declares is dropped rather than turned into a blank column.
     */
    @NotNull
    private static List<String> narrowToTargets(
        @NotNull WeaviateQuerySpec spec,
        @NotNull List<String> declared
    ) {
        List<String> targets = targetVectorNames(spec);
        if (targets.isEmpty()) {
            return declared;
        }
        List<String> out = new ArrayList<>(targets.size());
        for (String name : targets) {
            if (declared.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * Distinct target names of a spec, in order, or empty when it names none.
     * <p>
     * The one rule for which vectors a query is about: readData builds the grid's vector columns
     * from it and applyCommon asks the server for exactly those. Deriving the two separately is
     * how columns come to be permanently blank, or data to arrive with nowhere to go.
     */
    @NotNull
    private static List<String> targetVectorNames(@NotNull WeaviateQuerySpec spec) {
        List<String> names = new ArrayList<>(spec.getTargets().size());
        for (WeaviateVectorTarget target : spec.getTargets()) {
            if (!names.contains(target.getName())) {
                names.add(target.getName());
            }
        }
        return names;
    }

    /**
     * Metadata to request for a scored query. The explanation is only asked for when it will be
     * shown -- producing it is extra server-side work for a column nobody looked at.
     */
    @NotNull
    private static Metadata[] scoreMetadata(@NotNull WeaviateQuerySpec spec) {
        return spec.isExplainScore()
            ? new Metadata[]{Metadata.SCORE, Metadata.EXPLAIN_SCORE}
            : new Metadata[]{Metadata.SCORE};
    }

    @NotNull
    private static String requireQuery(@NotNull WeaviateQuerySpec spec, @NotNull String modeLabel) {
        String text = spec.getQuery();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(modeLabel + " mode requires a query string");
        }
        return text;
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
        if (hasRerankScore(spec)) {
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

    @NotNull
    private static List<SortBy> buildSortBy(
        @Nullable DBDDataFilter dataFilter,
        @NotNull List<WeaviateProperty> attributes
    ) {
        if (dataFilter == null) {
            return Collections.emptyList();
        }
        List<DBDAttributeConstraint> ordered = new ArrayList<>();
        for (DBDAttributeConstraint c : dataFilter.getConstraints()) {
            if (c.getOrderPosition() > 0) {
                ordered.add(c);
            }
        }
        if (ordered.isEmpty()) {
            return Collections.emptyList();
        }
        ordered.sort((a, b) -> Integer.compare(a.getOrderPosition(), b.getOrderPosition()));
        List<SortBy> out = new ArrayList<>(ordered.size());
        for (DBDAttributeConstraint c : ordered) {
            String name = c.getAttribute() != null ? c.getAttribute().getName() : c.getAttributeName();
            if (name == null || name.isEmpty()) continue;
            SortBy sort = mapSortBy(name);
            out.add(c.isOrderDescending() ? sort.desc() : sort.asc());
        }
        return out;
    }

    @NotNull
    private static SortBy mapSortBy(@NotNull String name) {
        if (WeaviateColumns.UUID.equalsIgnoreCase(name)) {
            return SortBy.uuid();
        }
        return SortBy.property(name);
    }

    @NotNull
    private static String describeQuery(
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
    private static void describeTargets(@NotNull WeaviateQuerySpec spec, @NotNull List<String> args) {
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
    private static String quote(@Nullable String s) {
        return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
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
     * True when the collection partitions its objects by tenant.
     * <p>
     * Weaviate rejects a query against such a collection unless it names a tenant, so the data
     * view has to ask which one before it can show anything.
     */
    public boolean isMultiTenant() {
        return config.multiTenancy() != null && config.multiTenancy().enabled();
    }

    /**
     * Whether writing to an unknown tenant creates it, or null when the server never said.
     * <p>
     * Null is not the same as false. Weaviate omits these fields entirely on a server older than
     * 1.25.2, and a UI that renders the absence as an unticked box invites someone to "fix" a
     * setting that does not exist.
     */
    @Nullable
    public Boolean getAutoTenantCreation() {
        return config.multiTenancy() == null ? null : config.multiTenancy().createAutomatically();
    }

    /** Whether reading an inactive tenant wakes it. See {@link #getAutoTenantCreation()} on null. */
    @Nullable
    public Boolean getAutoTenantActivation() {
        return config.multiTenancy() == null ? null : config.multiTenancy().activateAutomatically();
    }

    /**
     * Turns automatic tenant creation and activation on or off.
     * <p>
     * Both travel in one request because they are one object to the server: the update carries a
     * whole multiTenancy block, so sending only one of them would silently reset the other to the
     * client's default.
     * <p>
     * {@code enabled} is always passed through unchanged. Weaviate will not switch multi-tenancy
     * itself on or off after a collection exists, and leaving it out of the block would ask it to.
     */
    public void setAutoTenantOptions(
        @NotNull DBRProgressMonitor monitor,
        boolean autoCreation,
        boolean autoActivation
    ) throws DBException {
        if (!isMultiTenant()) {
            throw new DBException(getName() + " is not multi-tenant");
        }
        monitor.subTask("Update multi-tenancy settings of " + getName());
        try {
            dataSource.getClient().collections.use(getName()).config.update(
                b -> b.multiTenancy(mt -> mt
                    .enabled(true)
                    .autoTenantCreation(autoCreation)
                    .autoTenantActivation(autoActivation)));
            // Read back rather than patching the local copy: the server is free to refuse or
            // adjust, and the checkboxes must show what it actually holds.
            refreshConfig();
        } catch (Exception e) {
            throw new DBException(
                "Cannot update multi-tenancy settings of " + getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Re-reads this collection's definition from the server and drops everything derived from it.
     */
    public void refreshConfig() throws DBException {
        try {
            CollectionConfig fresh = dataSource.getClient().collections.getConfig(getName())
                .orElseThrow(() -> new DBException(getName() + " no longer exists"));
            this.config = fresh;
            this.definitionNodes = null;
            resetTenantCache();
        } catch (DBException e) {
            throw e;
        } catch (Exception e) {
            throw new DBException("Cannot re-read " + getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Tenants defined for this collection with their states, ordered by name.
     * <p>
     * One request, no paging, even for a collection with thousands of tenants: the server answers
     * with the whole list and there is no endpoint that returns part of it. Measured against a
     * 4000-tenant collection this is around 190 KiB and 25 ms, so the cost of holding them all is
     * not what limits the UI -- rendering them is. Reading is the uncapped direction; writing
     * back is not, see {@link #TENANT_UPDATE_CHUNK}.
     */
    @NotNull
    public List<WeaviateTenant> listTenants(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!isMultiTenant()) {
            return List.of();
        }
        monitor.subTask("Read tenants of " + getName());
        try {
            List<WeaviateTenant> tenants = new ArrayList<>();
            for (Tenant tenant : dataSource.getClient().collections.use(getName()).tenants.list()) {
                if (tenant.name() != null && !tenant.name().isBlank()) {
                    tenants.add(new WeaviateTenant(
                        tenant.name(),
                        WeaviateTenantStatus.fromName(
                            tenant.status() == null ? null : tenant.status().name())));
                }
            }
            tenants.sort(Comparator.comparing(WeaviateTenant::name));
            return tenants;
        } catch (Exception e) {
            throw new DBException(
                "Cannot list tenants of " + getName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Tenants as navigator nodes, for the Tenants folder under a multi-tenant collection.
     * <p>
     * Cached, because the navigator asks repeatedly while painting labels, and dropped whenever
     * a state changes so the tree cannot go on showing a tenant as active after it was switched
     * off. A collection with thousands of tenants makes this folder large, which is the same
     * bargain the platform already makes for a schema with thousands of tables: it is lazy, so
     * nothing is read until someone expands it.
     */
    @Association
    public List<WeaviateTenantNode> getTenantNodes(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!isMultiTenant()) {
            return List.of();
        }
        if (tenantNodes == null) {
            List<WeaviateTenant> tenants = listTenants(monitor);
            List<WeaviateTenantNode> nodes = new ArrayList<>(tenants.size());
            for (WeaviateTenant tenant : tenants) {
                nodes.add(new WeaviateTenantNode(this, tenant));
            }
            tenantNodes = nodes;
        }
        return tenantNodes;
    }

    /**
     * Forgets the cached tenant nodes. Called after any change of state, and available to the UI
     * so a navigator refresh shows what the server now holds.
     */
    public void resetTenantCache() {
        tenantNodes = null;
    }

    /**
     * How many tenants go in one activate/deactivate request.
     * <p>
     * This is the server's hard limit, not a tuning choice. Weaviate rejects an update naming
     * more than 100 tenants with
     * {@code 422 maximum number of tenants allowed to be updated simultaneously is 100}
     * ({@code usecases/schema/tenant.go}, {@code validateTenants(..., allowOverHundred=false)}).
     * Creating tenants is uncapped -- only updates are limited -- which is why seeding thousands
     * works and switching them off in one go does not.
     * <p>
     * Chunking also buys a progress bar that moves and a Cancel that can be honoured between
     * chunks, but those are the secondary reasons. The limit is the reason.
     */
    public static final int TENANT_UPDATE_CHUNK = 100;

    /**
     * Activates or deactivates the named tenants, and reports how many actually changed.
     * <p>
     * Tenants already in the wanted state are dropped before anything is sent. The server would
     * accept them, but the count returned is shown to the user, and "deactivated 750" is a false
     * statement when 700 of them were already inactive.
     * <p>
     * Cancelling stops at a chunk boundary, so the work already sent stands. That is why the
     * dialog re-reads the list afterwards instead of assuming what it asked for is what happened.
     *
     * @return the number of tenants whose state was changed
     */
    public int setTenantStatus(
        @NotNull DBRProgressMonitor monitor,
        @NotNull List<WeaviateTenant> tenants,
        @NotNull WeaviateTenantStatus target
    ) throws DBException {
        if (!target.isSettable()) {
            // OFFLOADED needs an offload module rather than a version, and the two transitional
            // states belong to the server. Refusing here keeps that decision in one place.
            throw new DBException("Tenants cannot be set to " + target.getLabel());
        }
        List<String> pending = new ArrayList<>();
        for (WeaviateTenant tenant : tenants) {
            if (tenant.status() != target) {
                pending.add(tenant.name());
            }
        }
        if (pending.isEmpty()) {
            return 0;
        }

        boolean activate = target == WeaviateTenantStatus.ACTIVE;
        monitor.beginTask(
            (activate ? "Activate " : "Deactivate ") + pending.size() + " tenants of " + getName(),
            pending.size());
        try {
            var tenantsClient = dataSource.getClient().collections.use(getName()).tenants;
            int done = 0;
            for (int from = 0; from < pending.size(); from += TENANT_UPDATE_CHUNK) {
                if (monitor.isCanceled()) {
                    break;
                }
                List<String> chunk = pending.subList(
                    from, Math.min(from + TENANT_UPDATE_CHUNK, pending.size()));
                monitor.subTask(chunk.get(0) + (chunk.size() > 1 ? " and " + (chunk.size() - 1) + " more" : ""));
                if (activate) {
                    tenantsClient.activate(chunk);
                } else {
                    tenantsClient.deactivate(chunk);
                }
                done += chunk.size();
                monitor.worked(chunk.size());
            }
            return done;
        } catch (Exception e) {
            throw new DBException("Cannot update tenants of " + getName() + ": " + e.getMessage(), e);
        } finally {
            // Whatever happened, including a cancel partway, the cached states are now suspect.
            resetTenantCache();
            monitor.done();
        }
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
    private CollectionHandle<Map<String, Object>> handle(@Nullable String tenant) {
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

    /**
     * Ask which tenant to read, when the data view is opened on a multi-tenant collection.
     * <p>
     * The choice cannot be defaulted: reading the wrong tenant returns real rows that are simply
     * someone else's, which is worse than showing nothing. Only interactive reads prompt -- an
     * export runs unattended and must not block on a dialog.
     *
     * @return the chosen tenant, or null if the user declined or there are none
     */
    @Nullable
    private String promptForTenant(
        @NotNull DBCSession session,
        @NotNull DBRProgressMonitor monitor,
        @NotNull WeaviateQuerySpec spec
    ) {
        if (!session.getPurpose().isUser()) {
            return null;
        }
        List<WeaviateTenant> tenants;
        try {
            tenants = listTenants(monitor);
        } catch (DBException e) {
            queryLog.warn("Cannot list tenants of " + getName(), e);
            return null;
        }
        if (tenants.isEmpty()) {
            return null;
        }
        // The searchable picker handles any number of tenants, so prefer it whenever the UI
        // bundle has registered one.
        WeaviateTenantPrompt prompt = WeaviateTenantPrompt.getProvider();
        if (prompt != null) {
            return prompt.selectTenant(getName(), tenants, spec.getTenant());
        }
        if (tenants.size() > MAX_TENANTS_TO_PROMPT) {
            // Fallback only. The platform dialog lays options out as a row of buttons, so past a
            // handful it is unusable; better to say nothing and let the message point at the
            // "Select Tenant..." command.
            queryLog.debug(getName() + " has " + tenants.size()
                + " tenants and no searchable picker is registered");
            return null;
        }
        // The fallback dialog takes plain labels, so the state is spelled into them -- it is the
        // one thing that decides whether the chosen tenant can actually be read.
        List<String> labels = new ArrayList<>(tenants.size());
        for (WeaviateTenant tenant : tenants) {
            labels.add(tenant.isActive()
                ? tenant.name()
                : tenant.name() + " (" + tenant.status().getLabel() + ")");
        }
        DBPPlatformUI.UserChoiceResponse response = DBWorkbench.getPlatformUI().showUserChoice(
            "Select tenant",
            "\"" + getName() + "\" is a multi-tenant collection. Choose which tenant's data to show.",
            labels,
            List.of(),
            null,
            0);
        if (response.choiceIndex < 0 || response.choiceIndex >= tenants.size()) {
            return null;
        }
        return tenants.get(response.choiceIndex).name();
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
        return new DeleteBatch(uuidIndex, tenant);
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

    /**
     * Collects the UUIDs of the rows marked for deletion and removes them in one request.
     * <p>
     * DBeaver calls {@link #add} once per row and {@link #execute} once when the user saves, so
     * batching here turns "delete 200 selected rows" into a single call rather than 200.
     */
    private final class DeleteBatch implements ExecuteBatch {

        private final int uuidIndex;
        private final String tenant;
        private final List<String> ids = new ArrayList<>();

        DeleteBatch(int uuidIndex, @Nullable String tenant) {
            this.uuidIndex = uuidIndex;
            this.tenant = tenant;
        }

        @NotNull
        @Override
        public ExecuteBatch add(@NotNull Object[] attributeValues) throws DBCException {
            if (uuidIndex >= attributeValues.length) {
                throw new DBCException("Row has no " + WeaviateColumns.UUID + " value to delete by");
            }
            Object value = attributeValues[uuidIndex];
            String id = value == null ? null : value.toString().trim();
            if (id == null || id.isEmpty()) {
                throw new DBCException("Cannot delete a row with an empty " + WeaviateColumns.UUID);
            }
            ids.add(id);
            return this;
        }

        @NotNull
        @Override
        public DBCStatistics execute(
            @NotNull DBCSession session,
            @NotNull Map<String, Object> options
        ) throws DBException {
            DBCStatistics statistics = new DBCStatistics();
            if (ids.isEmpty()) {
                return statistics;
            }
            long startTime = System.currentTimeMillis();
            statistics.setQueryText("DELETE " + ids.size() + " object(s) FROM " + getName());
            try {
                DeleteManyResponse response = handle(tenant)
                    .data.deleteMany(
                        Filter.uuid().containsAny(ids.toArray(new String[0])),
                        b -> b.verbose(true));
                long failed = response.failed();
                if (failed > 0) {
                    // Surfaced rather than swallowed: the grid would otherwise drop the rows
                    // locally and look as though the delete had succeeded.
                    throw new DBCException(
                        "Weaviate deleted " + response.successful() + " of " + ids.size()
                            + " object(s); " + failed + " failed" + firstError(response));
                }
                statistics.setRowsUpdated(response.successful());
            } catch (DBCException e) {
                throw e;
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                queryLog.warn("Weaviate delete failed for collection " + getName() + ": " + msg, e);
                throw new DBCException("Failed to delete from " + getName() + ": " + msg, e);
            } finally {
                statistics.setExecuteTime(System.currentTimeMillis() - startTime);
                ids.clear();
            }
            return statistics;
        }

        @Override
        public void generatePersistActions(
            @NotNull DBCSession session,
            @NotNull List<DBEPersistAction> actions,
            @NotNull Map<String, Object> options
        ) {
            // "Generate SQL" for the pending changes. There is no SQL dialect behind Weaviate,
            // so the best that can be offered is a readable description of what would be sent.
            for (String id : ids) {
                actions.add(new SQLDatabasePersistActionComment(
                    getDataSource(),
                    "Delete object " + id + " from " + getName()));
            }
        }

        @Override
        public void close() {
            ids.clear();
        }
    }

    @NotNull
    private static String firstError(@NotNull DeleteManyResponse response) {
        if (response.objects() == null) {
            return "";
        }
        for (DeleteManyResponse.DeletedObject o : response.objects()) {
            if (!o.successful() && o.error() != null) {
                return ": " + o.error();
            }
        }
        return "";
    }

    @Nullable
    public String getLastQueryError() {
        return lastQueryError;
    }

    public void clearLastQueryError() {
        this.lastQueryError = null;
    }

    @Nullable
    public String getLastGenerativeGroupedResult() {
        return lastGenerativeGroupedResult;
    }
}
