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

import io.weaviate.client6.v1.api.collections.CollectionConfig;
import io.weaviate.client6.v1.api.collections.Property;
import io.weaviate.client6.v1.api.collections.Reranker;
import io.weaviate.client6.v1.api.collections.VectorConfig;
import io.weaviate.client6.v1.api.collections.WeaviateObject;
import io.weaviate.client6.v1.api.collections.aggregate.AggregateResponse;
import io.weaviate.client6.v1.api.collections.query.Filter;
import io.weaviate.client6.v1.api.collections.query.Metadata;
import io.weaviate.client6.v1.api.collections.query.QueryResponse;
import io.weaviate.client6.v1.api.collections.query.SortBy;
import io.weaviate.client6.v1.api.collections.query.WeaviateQueryClient;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeConstraint;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.model.exec.DBCException;
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
    private final CollectionConfig config;
    private volatile List<WeaviateProperty> attributes;
    private volatile WeaviateQuerySpec querySpec;
    private volatile String lastQueryError;

    public WeaviateCollection(@NotNull WeaviateDataSource dataSource, @NotNull CollectionConfig config) {
        this.dataSource = dataSource;
        this.config = config;
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
    public boolean isPersisted() {
        return true;
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

        List<WeaviateProperty> attributes = getAttributes(monitor);
        List<String> columnNames = new ArrayList<>(attributes.size() + 3);
        columnNames.add(WeaviateColumns.UUID);
        for (WeaviateProperty p : attributes) {
            columnNames.add(p.getName());
        }
        columnNames.add(WeaviateColumns.SCORE);
        columnNames.add(WeaviateColumns.DISTANCE);

        WeaviateQuerySpec spec = getQuerySpec();
        Filter filter = WeaviateFilterTranslator.translate(dataFilter);
        List<SortBy> sortBy = spec.rankedResults() ? Collections.emptyList() : buildSortBy(dataFilter, attributes);
        int limit = maxRows > 0 ? (int) Math.min(maxRows, Integer.MAX_VALUE) : 0;
        int offset = firstRow > 0 ? (int) Math.min(firstRow, Integer.MAX_VALUE) : 0;

        String queryText = describeQuery(spec, filter, sortBy, limit, offset);
        statistics.setQueryText(queryText);

        long startTime = System.currentTimeMillis();
        QueryResponse<Map<String, Object>> response = null;
        try {
            response = executeQuery(spec, filter, sortBy, limit, offset);
            lastQueryError = null;
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            lastQueryError = "Query failed: " + msg;
            queryLog.warn("Weaviate query failed for collection " + getName() + ": " + msg, e);
            queryText = queryText + " — ERROR: " + msg;
            statistics.setQueryText(queryText);
        }
        statistics.setExecuteTime(System.currentTimeMillis() - startTime);
        if (monitor.isCanceled()) {
            return statistics;
        }

        try (LocalStatement statement = new LocalStatement(session, queryText)) {
            statement.setStatementSource(source);
            LocalResultSet<LocalStatement> resultSet = new LocalResultSet<>(session, statement);
            populateColumns(resultSet, attributes);
            if (response != null) {
                for (WeaviateObject<Map<String, Object>> obj : response.objects()) {
                    resultSet.addRow(WeaviateRowMapper.toRow(columnNames, obj));
                }
            }
            DBDDataReceiver.startFetchWorkflow(dataReceiver, session, resultSet, firstRow, maxRows);
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
                    applyCommon(b, filter, limit, offset);
                    b.returnMetadata(Metadata.SCORE);
                    if (!queryProperties.isEmpty()) b.queryProperties(queryProperties);
                    return b;
                });
            }
            case NEAR_TEXT: {
                String text = requireQuery(spec, "Near Text");
                Float distance = spec.getDistance();
                return query.nearText(text, b -> {
                    applyCommon(b, filter, limit, offset);
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
                    applyCommon(b, filter, limit, offset);
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
                    applyCommon(b, filter, limit, offset);
                    b.returnMetadata(Metadata.SCORE, Metadata.DISTANCE);
                    if (alpha != null) b.alpha(alpha);
                    if (fusion != null) b.fusionType(fusion.toClientType());
                    return b;
                });
            }
            case FETCH:
            default:
                return query.fetchObjects(b -> {
                    applyCommon(b, filter, limit, offset);
                    if (!sortBy.isEmpty()) b.sort(sortBy);
                    return b;
                });
        }
    }

    private static <B extends io.weaviate.client6.v1.api.collections.query.BaseQueryOptions.Builder<B, ?>>
    void applyCommon(@NotNull B b, @Nullable Filter filter, int limit, int offset) {
        if (limit > 0) b.limit(limit);
        if (offset > 0) b.offset(offset);
        if (filter != null) b.filters(filter);
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
        Filter filter = WeaviateFilterTranslator.translate(dataFilter);
        try {
            AggregateResponse response = dataSource.getClient().collections.use(getName())
                .aggregate.overAll(b -> {
                    b.includeTotalCount(true);
                    if (filter != null) b.filters(filter);
                    return b;
                });
            Long total = response.totalCount();
            return total == null ? -1 : total;
        } catch (Exception e) {
            throw new DBCException("Failed to count objects in collection " + getName(), e, session.getExecutionContext());
        }
    }

    private static void populateColumns(
        @NotNull LocalResultSet<LocalStatement> rs,
        @NotNull List<WeaviateProperty> attributes
    ) {
        rs.addColumn(WeaviateColumns.UUID, DBPDataKind.STRING);
        for (WeaviateProperty p : attributes) {
            rs.addColumn(p.getName(), p.getDataKind());
        }
        rs.addColumn(WeaviateColumns.SCORE, DBPDataKind.NUMERIC);
        rs.addColumn(WeaviateColumns.DISTANCE, DBPDataKind.NUMERIC);
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
        StringBuilder sb = new StringBuilder();
        switch (spec.getMode()) {
            case BM25: sb.append("bm25(query=").append(quote(spec.getQuery())); break;
            case NEAR_TEXT: sb.append("nearText(query=").append(quote(spec.getQuery())); break;
            case NEAR_VECTOR: sb.append("nearVector(dim=")
                .append(spec.getVector() == null ? 0 : spec.getVector().length); break;
            case HYBRID:
                sb.append("hybrid(query=").append(quote(spec.getQuery()));
                if (spec.getAlpha() != null) sb.append(", alpha=").append(spec.getAlpha());
                break;
            case FETCH:
            default: sb.append("fetchObjects(");
        }
        if (sb.charAt(sb.length() - 1) != '(') sb.append(", ");
        if (limit > 0) sb.append("limit=").append(limit).append(", ");
        if (offset > 0) sb.append("offset=").append(offset).append(", ");
        if (filter != null) sb.append("filter=").append(filter).append(", ");
        if (!sortBy.isEmpty()) sb.append("sort=").append(sortBy).append(", ");
        if (sb.charAt(sb.length() - 1) == ' ') sb.setLength(sb.length() - 2);
        if (sb.charAt(sb.length() - 1) == '(') sb.setLength(sb.length() - 1);
        else sb.append(")");
        if (sb.charAt(sb.length() - 1) != ')') sb.append(")");
        return sb.toString();
    }

    @NotNull
    private static String quote(@Nullable String s) {
        return s == null ? "null" : "\"" + s.replace("\"", "\\\"") + "\"";
    }

    @NotNull
    public WeaviateQuerySpec getQuerySpec() {
        WeaviateQuerySpec s = querySpec;
        return s == null ? WeaviateQuerySpec.fetch() : s;
    }

    public void setQuerySpec(@Nullable WeaviateQuerySpec spec) {
        this.querySpec = spec;
    }

    @Nullable
    public String getLastQueryError() {
        return lastQueryError;
    }

    public void clearLastQueryError() {
        this.lastQueryError = null;
    }
}
