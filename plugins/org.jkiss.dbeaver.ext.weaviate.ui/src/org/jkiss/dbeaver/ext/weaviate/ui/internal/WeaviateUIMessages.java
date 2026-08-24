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
package org.jkiss.dbeaver.ext.weaviate.ui.internal;

import org.eclipse.osgi.util.NLS;

public final class WeaviateUIMessages extends NLS {
    private static final String BUNDLE_NAME = "org.jkiss.dbeaver.ext.weaviate.ui.internal.WeaviateUIMessages"; //$NON-NLS-1$

    static {
        NLS.initializeMessages(BUNDLE_NAME, WeaviateUIMessages.class);
    }

    // Connection page
    public static String connection_type_label;
    public static String connection_type_cloud;
    public static String connection_type_custom;
    public static String connection_cluster_url;
    public static String connection_scheme;
    public static String connection_host;
    public static String connection_port;
    public static String connection_grpc_host;
    public static String connection_grpc_port;
    public static String connection_auth_group;
    public static String connection_auth_type;
    public static String connection_auth_none;
    public static String connection_auth_api_key;
    public static String connection_auth_user_password;
    public static String connection_api_key;
    public static String connection_username;
    public static String connection_password;
    public static String connection_data_group;
    public static String connection_include_vectors;
    public static String connection_include_vectors_tip;

    // Query panel
    public static String query_mode;
    public static String query_query;
    public static String query_vector;
    public static String query_alpha;
    public static String query_distance;
    public static String query_fusion;
    public static String query_properties;
    public static String query_match;
    public static String query_match_all;
    public static String query_match_any;
    public static String query_filters;
    public static String query_include_vector;
    public static String query_include_vector_tip;
    public static String query_reset;
    public static String query_reset_tip;
    public static String query_fetch_hint;
    public static String query_bm25_properties_tip;
    public static String query_running;
    public static String query_dismiss;
    public static String query_remove_filter;
    public static String query_search_hint;
    public static String query_distance_hint;
    public static String query_failed;
    public static String query_not_weaviate;
    public static String query_required_bm25;
    public static String query_required_near_text;
    public static String query_required_hybrid;
    public static String query_required_vector;
    public static String query_invalid_vector;

    // Collection definition dialog
    public static String collection_dialog_title;
    public static String collection_dialog_json_label;
    public static String collection_dialog_builder_link;
    public static String collection_dialog_load_file;
    public static String collection_dialog_read_error;

    // Schema export
    public static String export_schema_title;
    public static String export_schema_error;

    // Documentation links
    public static String docs_read_docs_on;

    // Model provider API keys
    public static String model_keys_title;
    public static String model_keys_description;
    public static String model_keys_providers_group;
    public static String model_keys_custom_group;
    public static String model_keys_header_column;
    public static String model_keys_value_column;
    public static String model_keys_add;
    public static String model_keys_remove;
    public static String model_keys_tooltip_header;
    public static String model_keys_tooltip_env;
    public static String model_keys_tooltip_env_active;
    public static String model_keys_tooltip_env_none;
    public static String model_keys_env_hint;

    // Near Object query mode
    public static String query_object_id;
    public static String query_object_id_hint;
    public static String query_object_id_tip;
    public static String query_required_object_id;
    public static String query_invalid_object_id;

    // Multi-tenancy
    public static String query_tenant;
    public static String query_tenant_tip;
    public static String query_tenant_none;

    // Tenant selection
    public static String tenant_dialog_title;
    public static String tenant_dialog_title_plain;
    public static String tenant_dialog_prompt;
    public static String tenant_dialog_filter_hint;
    public static String tenant_dialog_count;
    public static String tenant_not_multi_tenant;
    public static String tenant_list_failed;
    public static String tenant_selected;

    // Hybrid alpha slider
    public static String query_alpha_keyword_end;
    public static String query_alpha_vector_end;
    public static String query_alpha_tip;

    // Autocut
    public static String query_autocut;
    public static String query_autocut_tip;
    public static String query_generative;
    public static String query_generative_tip;
    public static String query_generative_single;
    public static String query_generative_single_hint;
    public static String query_generative_grouped;
    public static String query_generative_grouped_hint;
    public static String query_generative_properties;
    public static String query_generative_properties_tip;
    public static String query_generative_provider;
    public static String query_generative_provider_default;
    public static String query_generative_provider_default_none;
    public static String query_generative_model;
    public static String query_generative_temperature;
    public static String query_generative_max_tokens;
    public static String query_generative_metadata;
    public static String query_generative_metadata_tip;
    public static String query_generative_grouped_result;
    public static String query_generative_props_without_grouped;
    public static String query_generative_params_without_provider;
    public static String query_generative_invalid_temperature;
    public static String query_generative_invalid_max_tokens;
    public static String query_rerank;
    public static String query_rerank_tip;
    public static String query_rerank_property;
    public static String query_rerank_none;
    public static String query_rerank_query;
    public static String query_rerank_query_hint;
    public static String query_rerank_module;
    public static String query_rerank_no_module;
    public static String query_rerank_property_required;
    public static String query_metadata;
    public static String query_metadata_created;
    public static String query_metadata_updated;
    public static String query_metadata_certainty;
    public static String query_metadata_tip;
    public static String query_targets;
    public static String query_targets_tip;
    public static String query_target_add;
    public static String query_target_remove;
    public static String query_target_join;
    public static String query_target_join_tip;
    public static String query_target_weight_hint;
    public static String query_target_vector_hint;
    public static String query_target_multi_vector_hint;
    public static String query_target_duplicate;
    public static String query_target_required;
    public static String query_target_weight_required;
    public static String query_target_weight_invalid;
    public static String query_target_vector_required;
    public static String query_target_vector_invalid;
    public static String query_target_expects_flat;

    // Explain score
    public static String query_explain_score;
    public static String query_explain_score_tip;

    private WeaviateUIMessages() {
    }
}
