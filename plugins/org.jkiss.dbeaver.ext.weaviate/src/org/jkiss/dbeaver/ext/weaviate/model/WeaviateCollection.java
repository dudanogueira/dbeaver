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
import io.weaviate.client6.v1.api.collections.Property;
import io.weaviate.client6.v1.api.collections.Reranker;
import io.weaviate.client6.v1.api.collections.VectorConfig;
import io.weaviate.client6.v1.api.collections.WeaviateObject;
import io.weaviate.client6.v1.api.collections.aggregate.AggregateResponse;
import io.weaviate.client6.v1.api.collections.pagination.Paginator;
import io.weaviate.client6.v1.api.collections.query.Filter;
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
import org.jkiss.dbeaver.model.exec.DBCExecutionSource;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatistics;
import org.jkiss.dbeaver.model.impl.local.LocalResultSet;
import org.jkiss.dbeaver.model.impl.local.LocalStatement;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAssociation;
import org.jkiss.dbeaver.model.struct.DBSEntityConstraint;
import org.jkiss.dbeaver.model.struct.DBSEntityType;
import org.jkiss.dbeaver.model.struct.DBSObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class WeaviateCollection implements DBSEntity, DBSDataContainer {

    private static final String[] SUPPORTED_FEATURES = new String[]{
        FEATURE_DATA_SELECT,
        FEATURE_DATA_COUNT,
        FEATURE_DATA_FILTER,
    };

    private static final org.jkiss.dbeaver.Log queryLog = org.jkiss.dbeaver.Log.getLog(WeaviateCollection.class);

    private final WeaviateDataSource dataSource;
    /** Replaced once by {@link #markPersisted} when a pending collection is created on the server. */
    private volatile CollectionConfig config;
    private volatile boolean persisted;
    private volatile List<WeaviateProperty> attributes;
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

    @Override
    public List<WeaviateProperty> getAttributes(@NotNull DBRProgressMonitor monitor) throws DBException {
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
        List<WeaviateProperty> result = new ArrayList<>(props.size());
        for (int i = 0; i < props.size(); i++) {
            result.add(new WeaviateProperty(this, props.get(i), i));
        }
        return result;
    }

    @Override
    public WeaviateProperty getAttribute(@NotNull DBRProgressMonitor monitor, @NotNull String attributeName) throws DBException {
        return DBUtils.findObject(getAttributes(monitor), attributeName);
    }

    @Override
    public Collection<? extends DBSEntityConstraint> getConstraints(@NotNull DBRProgressMonitor monitor) {
        return Collections.emptyList();
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
        List<WeaviateProperty> attributes = getAttributes(monitor);
        List<String> vectorNames = spec.isIncludeVector() ? getVectorNames() : Collections.emptyList();
        boolean singleVector = vectorNames.size() <= 1;

        List<String> columnNames = new ArrayList<>(attributes.size() + 3 + vectorNames.size());
        columnNames.add(WeaviateColumns.UUID);
        for (WeaviateProperty p : attributes) {
            columnNames.add(p.getName());
        }
        for (String vectorName : vectorNames) {
            columnNames.add(WeaviateColumns.vectorColumn(vectorName, singleVector));
        }
        columnNames.add(WeaviateColumns.SCORE);
        columnNames.add(WeaviateColumns.DISTANCE);

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
            populateColumns(resultSet, attributes, vectorNames, singleVector);
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
            populateColumns(resultSet, attributes, vectorNames, singleVector);
            try {
                Paginator<Map<String, Object>> paginator =
                    dataSource.getClient().collections.use(getName()).paginate(b -> {
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
            dataSource.getClient().collections.use(getName()).query;
        switch (spec.getMode()) {
            case BM25: {
                String text = requireQuery(spec, "BM25");
                List<String> queryProperties = spec.getQueryProperties();
                return query.bm25(text, b -> {
                    applyCommon(b, spec, filter, limit, offset);
                    b.returnMetadata(Metadata.SCORE);
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
            case HYBRID: {
                String text = requireQuery(spec, "Hybrid");
                Float alpha = spec.getAlpha();
                WeaviateHybridFusion fusion = spec.getFusionType();
                return query.hybrid(text, b -> {
                    applyCommon(b, spec, filter, limit, offset);
                    b.returnMetadata(Metadata.SCORE, Metadata.DISTANCE);
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
            AggregateResponse response = dataSource.getClient().collections.use(getName())
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
        boolean singleVector
    ) {
        rs.addColumn(WeaviateColumns.UUID, DBPDataKind.STRING);
        for (WeaviateProperty p : attributes) {
            rs.addColumn(p.getName(), p.getDataKind());
        }
        for (String vectorName : vectorNames) {
            // Rendered as text - see WeaviateRowMapper#readVector.
            rs.addColumn(WeaviateColumns.vectorColumn(vectorName, singleVector), DBPDataKind.STRING);
        }
        rs.addColumn(WeaviateColumns.SCORE, DBPDataKind.NUMERIC);
        rs.addColumn(WeaviateColumns.DISTANCE, DBPDataKind.NUMERIC);
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

    @Nullable
    public String getLastQueryError() {
        return lastQueryError;
    }

    public void clearLastQueryError() {
        this.lastQueryError = null;
    }
}
