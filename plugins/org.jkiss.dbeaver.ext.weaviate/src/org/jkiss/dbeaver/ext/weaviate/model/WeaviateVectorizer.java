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

import io.weaviate.client6.v1.api.collections.VectorConfig;
import io.weaviate.client6.v1.api.collections.VectorIndex;
import io.weaviate.client6.v1.api.collections.vectorindex.MultiVector;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.meta.Association;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class WeaviateVectorizer implements DBSObject, DBSObjectContainer {

    private static final Log log = Log.getLog(WeaviateVectorizer.class);

    private final WeaviateCollection collection;
    private final String vectorName;
    private final VectorConfig vectorConfig;
    private List<WeaviateMetadataField> fields;

    public WeaviateVectorizer(@NotNull WeaviateCollection collection, @NotNull String vectorName, @NotNull VectorConfig vectorConfig) {
        this.collection = collection;
        this.vectorName = vectorName;
        this.vectorConfig = vectorConfig;
    }

    @NotNull
    @Override
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 1)
    public String getName() {
        Object kind = vectorConfig._kind();
        return kind == null ? vectorName : vectorName + " (" + kind + ")";
    }

    /**
     * The bare vector name, without the kind suffix {@link #getName()} adds for the navigator
     * label. This is what a query names as its target, so it has to round-trip exactly.
     */
    @NotNull
    public String getVectorName() {
        return vectorName;
    }

    /**
     * Whether this vector's index stores a matrix per object (ColBERT-style) rather than a single
     * embedding, in which case a Near Vector search against it must supply a matrix too.
     * <p>
     * Only HNSW carries a multiVector component -- flat, dynamic and hFresh indexes have no such
     * setting -- so the isHnsw check is the whole story.
     */
    public boolean isMultiVector() {
        VectorIndex index = vectorConfig.vectorIndex();
        if (index == null || !index.isHnsw()) {
            return false;
        }
        MultiVector multiVector = index.asHnsw().multiVector();
        return multiVector != null && multiVector.enabled();
    }

    @Nullable
    @org.jkiss.dbeaver.model.meta.Property(viewable = true, order = 2)
    public String getKind() {
        Object kind = vectorConfig._kind();
        return kind == null ? null : kind.toString();
    }

    @Association
    public List<WeaviateMetadataField> getFields(@NotNull DBRProgressMonitor monitor) {
        if (fields == null) {
            fields = loadFields();
        }
        return fields;
    }

    /**
     * The vector's settings as the server reports them, not as the client models them.
     * <p>
     * Reflecting the client's {@code VectorConfig} loses most of what is worth seeing here. Its
     * {@code Hnsw} record models thirteen of the nineteen fields a 1.39 server sends for an index,
     * and the quantizers are not among them: the client lifts whichever one is switched on into a
     * separate {@code quantization} union and drops the other three entirely, so a collection with
     * no quantizer showed nothing at all and one with PQ showed it only obliquely. It also has no
     * field for {@code skipDefaultQuantization} or {@code trackDefaultQuantization}.
     * <p>
     * So this reads the definition instead, exactly as the Raw Definition folder does and for the
     * same reason. What the client cannot model still appears, and a field added by a newer server
     * appears without this plugin being changed.
     * <p>
     * Falls back to the client's view on an OIDC connection, where a REST call cannot be
     * authenticated: narrower, and better than an empty folder.
     */
    @NotNull
    private List<WeaviateMetadataField> loadFields() {
        try {
            WeaviateConfigDocument document =
                WeaviateConfigDocument.of(collection.readRawDefinition());
            String path = document.getKeys("vectorConfig").contains(vectorName)
                ? "vectorConfig." + vectorName
                : "";
            Map<String, String> flat = path.isEmpty()
                // The older shape: one unnamed index, its settings at the top level beside the
                // vectorizer's own name. Only those keys, so the rest of the definition -- the
                // properties, the replication config -- does not land in this folder.
                ? legacyFields(document)
                : document.flatten(path);
            List<WeaviateMetadataField> result = new ArrayList<>(flat.size());
            for (Map.Entry<String, String> entry : flat.entrySet()) {
                result.add(new WeaviateMetadataField(this, entry.getKey(), entry.getValue()));
            }
            return result;
        } catch (DBException | RuntimeException e) {
            log.debug("Cannot read the definition of " + collection.getName()
                + "; showing vector '" + vectorName + "' as the client models it", e);
            return WeaviateRecordIntrospect.toFields(this, vectorConfig);
        }
    }

    @NotNull
    private static Map<String, String> legacyFields(@NotNull WeaviateConfigDocument document) {
        Map<String, String> flat = new LinkedHashMap<>();
        for (String key : List.of("vectorizer", "vectorIndexType")) {
            String value = document.getString(key);
            if (value != null) {
                flat.put(key, value);
            }
        }
        document.flatten("vectorIndexConfig")
            .forEach((name, value) -> flat.put("vectorIndexConfig." + name, value));
        return flat;
    }

    @Nullable
    @Override
    public String getDescription() {
        return null;
    }

    @NotNull
    @Override
    public DBSObject getParentObject() {
        return collection;
    }

    @NotNull
    @Override
    public DBPDataSource getDataSource() {
        return collection.getDataSource();
    }

    @Override
    public boolean isPersisted() {
        return true;
    }

    @NotNull
    @Override
    public Collection<WeaviateMetadataField> getChildren(@NotNull DBRProgressMonitor monitor) {
        return getFields(monitor);
    }

    @Nullable
    @Override
    public WeaviateMetadataField getChild(@NotNull DBRProgressMonitor monitor, @NotNull String childName) {
        for (WeaviateMetadataField f : getFields(monitor)) {
            if (childName.equals(f.getName())) return f;
        }
        return null;
    }

    @NotNull
    @Override
    public Class<? extends DBSObject> getPrimaryChildType(@Nullable DBRProgressMonitor monitor) {
        return WeaviateMetadataField.class;
    }

    @Override
    public void cacheStructure(@NotNull DBRProgressMonitor monitor, int scope) throws DBException {
        getFields(monitor);
    }
}
