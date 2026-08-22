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
import io.weaviate.client6.v1.api.collections.query.Filter;
import io.weaviate.client6.v1.api.collections.tenants.Tenant;
import io.weaviate.client6.v1.api.collections.query.Metadata;
import io.weaviate.client6.v1.api.collections.query.QueryResponse;
import io.weaviate.client6.v1.api.collections.query.SortBy;
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
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
        List<WeaviateProperty> attributes = getProperties(monitor);
        List<String> vectorNames = includeVectors(session, spec) ? getVectorNames() : Collections.emptyList();
        boolean singleVector = vectorNames.size() <= 1;

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
        if (spec.getMode().hasScore()) {
            columnNames.add(WeaviateColumns.SCORE);
        }
        if (spec.getMode().hasExplainScore()) {
            columnNames.add(WeaviateColumns.EXPLAIN_SCORE);
        }
        if (spec.getMode().hasDistance()) {
            columnNames.add(WeaviateColumns.DISTANCE);
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

        String queryText = describeQuery(spec, filter, sortBy, limit, offset);
        statistics.setQueryText(queryText);

        // An unbounded plain fetch is an export ("extract in a single query"). Offset paging cannot
        // serve it: Weaviate refuses offsets past QUERY_MAXIMUM_RESULTS (10000 by default), so a
        // large collection would fail partway through. The cursor paginator walks the whole
        // collection server-side instead, with no such ceiling.
        if (maxRows <= 0 && spec.getMode() == WeaviateQueryMode.FETCH) {
            return readAllViaCursor(
                source, session, dataReceiver, statistics, spec, filter,
                attributes, columnNames, vectorNames, singleVector, queryText, firstRow);
        }

        long startTime = System.currentTimeMillis();
        QueryResponse<Map<String, Object>> response;
        try {
            response = executeQuery(spec, filter, sortBy, limit, offset);
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
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            lastQueryError = "Query failed: " + msg;
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
            populateColumns(resultSet, attributes, vectorNames, singleVector, spec.getMode());
            String defaultVectorName = singleVector && !vectorNames.isEmpty() ? vectorNames.get(0) : null;
            for (WeaviateObject<Map<String, Object>> obj : response.objects()) {
                resultSet.addRow(WeaviateRowMapper.toRow(columnNames, obj, defaultVectorName));
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
            populateColumns(resultSet, attributes, vectorNames, singleVector, spec.getMode());
            try {
                Paginator<Map<String, Object>> paginator =
                    handle(spec.getTenant()).paginate(b -> {
                        if (filter != null) b.filters(filter);
                        if (spec.isIncludeVector()) b.includeVector();
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
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                lastQueryError = "Query failed: " + msg;
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
            case BM25: {
                String text = requireQuery(spec, "BM25");
                List<String> queryProperties = spec.getQueryProperties();
                return query.bm25(text, b -> {
                    applyCommon(b, spec, filter, limit, offset);
                    b.returnMetadata(Metadata.SCORE, Metadata.EXPLAIN_SCORE);
                    if (!queryProperties.isEmpty()) b.queryProperties(queryProperties);
                    return b;
                });
            }
            case NEAR_TEXT: {
                String text = requireQuery(spec, "Near Text");
                Float distance = spec.getDistance();
                return query.nearText(text, b -> {
                    applyCommon(b, spec, filter, limit, offset);
                    b.returnMetadata(Metadata.DISTANCE);
                    if (distance != null) b.distance(distance);
                    return b;
                });
            }
            case NEAR_VECTOR: {
                float[] vector = spec.getVector();
                if (vector == null || vector.length == 0) {
                    throw new IllegalStateException("Near Vector mode requires a non-empty vector");
                }
                Float distance = spec.getDistance();
                return query.nearVector(vector, b -> {
                    applyCommon(b, spec, filter, limit, offset);
                    b.returnMetadata(Metadata.DISTANCE);
                    if (distance != null) b.distance(distance);
                    return b;
                });
            }
            case NEAR_OBJECT: {
                // Near Vector is handed a vector; Near Object is handed an object id and the
                // server resolves that object's stored vector itself, so this works even when
                // the reference object's vector is never returned to the client.
                String uuid = spec.getObjectId();
                if (uuid == null || uuid.isBlank()) {
                    throw new IllegalStateException(
                        "Near Object mode requires the UUID of a reference object");
                }
                Float distance = spec.getDistance();
                return query.nearObject(uuid, b -> {
                    applyCommon(b, spec, filter, limit, offset);
                    b.returnMetadata(Metadata.DISTANCE);
                    if (distance != null) b.distance(distance);
                    return b;
                });
            }
            case HYBRID: {
                String text = requireQuery(spec, "Hybrid");
                Float alpha = spec.getAlpha();
                WeaviateHybridFusion fusion = spec.getFusionType();
                return query.hybrid(text, b -> {
                    applyCommon(b, spec, filter, limit, offset);
                    b.returnMetadata(Metadata.SCORE, Metadata.EXPLAIN_SCORE);
                    if (alpha != null) b.alpha(alpha);
                    if (fusion != null) b.fusionType(fusion.toClientType());
                    return b;
                });
            }
            case FETCH:
            default:
                return query.fetchObjects(b -> {
                    applyCommon(b, spec, filter, limit, offset);
                    if (!sortBy.isEmpty()) b.sort(sortBy);
                    return b;
                });
        }
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
        if (spec.isIncludeVector()) b.includeVector();
        // The client calls Weaviate's autocut "autolimit"; the wire field is autocut.
        Integer autoCut = spec.getAutoCut();
        if (autoCut != null && autoCut > 0 && spec.getMode().supportsAutoCut()) {
            b.autolimit(autoCut);
        }
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
        @NotNull WeaviateQueryMode mode
    ) {
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
        if (mode.hasScore()) {
            rs.addColumn(WeaviateColumns.SCORE, DBPDataKind.NUMERIC);
        }
        if (mode.hasExplainScore()) {
            rs.addColumn(WeaviateColumns.EXPLAIN_SCORE, DBPDataKind.STRING);
        }
        if (mode.hasDistance()) {
            rs.addColumn(WeaviateColumns.DISTANCE, DBPDataKind.NUMERIC);
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
                args.add("dim=" + (spec.getVector() == null ? 0 : spec.getVector().length));
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
        if (limit > 0) args.add("limit=" + limit);
        if (offset > 0) args.add("offset=" + offset);
        if (filter != null) args.add("filter=" + filter);
        if (!sortBy.isEmpty()) args.add("sort=" + sortBy);
        return function + "(" + String.join(", ", args) + ")";
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
     * True when the collection partitions its objects by tenant.
     * <p>
     * Weaviate rejects a query against such a collection unless it names a tenant, so the data
     * view has to ask which one before it can show anything.
     */
    public boolean isMultiTenant() {
        return config.multiTenancy() != null && config.multiTenancy().enabled();
    }

    /**
     * Tenants defined for this collection, ordered by name. Empty for a single-tenant collection.
     */
    @NotNull
    public List<String> listTenantNames(@NotNull DBRProgressMonitor monitor) throws DBException {
        if (!isMultiTenant()) {
            return List.of();
        }
        try {
            List<String> names = new ArrayList<>();
            for (Tenant tenant : dataSource.getClient().collections.use(getName()).tenants.list()) {
                if (tenant.name() != null) {
                    names.add(tenant.name());
                }
            }
            names.sort(String::compareTo);
            return names;
        } catch (Exception e) {
            throw new DBException(
                "Cannot list tenants of " + getName() + ": " + e.getMessage(), e);
        }
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
        List<String> tenants;
        try {
            tenants = listTenantNames(monitor);
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
        DBPPlatformUI.UserChoiceResponse response = DBWorkbench.getPlatformUI().showUserChoice(
            "Select tenant",
            "\"" + getName() + "\" is a multi-tenant collection. Choose which tenant's data to show.",
            tenants,
            List.of(),
            null,
            0);
        if (response.choiceIndex < 0 || response.choiceIndex >= tenants.size()) {
            return null;
        }
        return tenants.get(response.choiceIndex);
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
}
