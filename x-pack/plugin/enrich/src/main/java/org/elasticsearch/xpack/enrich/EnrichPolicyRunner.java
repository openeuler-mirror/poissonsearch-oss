/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.enrich;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.get.GetIndexRequest;
import org.elasticsearch.action.admin.indices.get.GetIndexResponse;
import org.elasticsearch.action.admin.indices.refresh.RefreshRequest;
import org.elasticsearch.action.admin.indices.refresh.RefreshResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.index.reindex.ReindexAction;
import org.elasticsearch.index.reindex.ReindexRequest;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.xpack.core.enrich.EnrichPolicy;

public class EnrichPolicyRunner implements Runnable {

    private static final Logger logger = LogManager.getLogger(EnrichPolicyRunner.class);

    private static final String ENRICH_INDEX_NAME_BASE = ".enrich-";

    private final String policyName;
    private final EnrichPolicy policy;
    private final ActionListener<PolicyExecutionResult> listener;
    private final ClusterService clusterService;
    private final Client client;
    private final IndexNameExpressionResolver indexNameExpressionResolver;
    private final LongSupplier nowSupplier;

    EnrichPolicyRunner(String policyName, EnrichPolicy policy, ActionListener<PolicyExecutionResult> listener,
                       ClusterService clusterService, Client client, IndexNameExpressionResolver indexNameExpressionResolver,
                       LongSupplier nowSupplier) {
        this.policyName = policyName;
        this.policy = policy;
        this.listener = listener;
        this.clusterService = clusterService;
        this.client = client;
        this.indexNameExpressionResolver = indexNameExpressionResolver;
        this.nowSupplier = nowSupplier;
    }

    @Override
    public void run() {
        // Collect the source index information
        logger.info("Policy [{}]: Running enrich policy", policyName);
        final String sourceIndexPattern = policy.getIndexPattern();
        logger.debug("Policy [{}]: Checking source index [{}]", policyName, sourceIndexPattern);
        GetIndexRequest getIndexRequest = new GetIndexRequest().indices(sourceIndexPattern);
        client.admin().indices().getIndex(getIndexRequest, new ActionListener<GetIndexResponse>() {
            @Override
            public void onResponse(GetIndexResponse getIndexResponse) {
                validateMappings(getIndexResponse);
                prepareAndCreateEnrichIndex();
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }

    private Map<String, Object> getMappings(final GetIndexResponse getIndexResponse, final String sourceIndexName) {
        ImmutableOpenMap<String, ImmutableOpenMap<String, MappingMetaData>> mappings = getIndexResponse.mappings();
        ImmutableOpenMap<String, MappingMetaData> indexMapping = mappings.get(sourceIndexName);
        assert indexMapping.keys().size() == 1 : "Expecting only one type per index";
        MappingMetaData typeMapping = indexMapping.iterator().next().value;
        return typeMapping.sourceAsMap();
    }

    private void validateMappings(final GetIndexResponse getIndexResponse) {
        String[] sourceIndices = getIndexResponse.getIndices();
        logger.debug("Policy [{}]: Validating [{}] source mappings", policyName, sourceIndices);
        for (String sourceIndex : sourceIndices) {
            Map<String, Object> mapping = getMappings(getIndexResponse, sourceIndex);
            Set<?> properties = ((Map<?, ?>) mapping.get("properties")).keySet();
            if (properties == null) {
                listener.onFailure(
                    new ElasticsearchException(
                        "Enrich policy execution for [{}] failed. Could not read mapping for source [{}] included by pattern [{}]",
                        policyName, sourceIndex, policy.getIndexPattern()));
            }
            if (properties.contains(policy.getEnrichKey()) == false) {
                listener.onFailure(
                    new ElasticsearchException(
                        "Enrich policy execution for [{}] failed. Could not locate enrich key field [{}] on mapping for index [{}]",
                        policyName, policy.getEnrichKey(), sourceIndex));
            }
        }
    }

    private String getEnrichIndexBase(final String policyName) {
        return ENRICH_INDEX_NAME_BASE + policyName;
    }

    private XContentBuilder resolveEnrichMapping(final EnrichPolicy policy) {
        // Currently the only supported policy type is EnrichPolicy.EXACT_MATCH_TYPE, which is a keyword type
        String keyType;
        if (EnrichPolicy.EXACT_MATCH_TYPE.equals(policy.getType())) {
            keyType = "keyword";
        } else {
            throw new ElasticsearchException("Unrecognized enrich policy type [{}]", policy.getType());
        }

        // Disable _source on enrich index. Explicitly mark key mapping type.
        try {
            XContentBuilder builder = JsonXContent.contentBuilder();
            builder.startObject()
                .startObject(MapperService.SINGLE_MAPPING_NAME)
                    .field("dynamic", false)
                    .startObject("_source")
                        .field("enabled", true)
                    .endObject()
                    .startObject("properties")
                        .startObject(policy.getEnrichKey())
                            .field("type", keyType)
                            .field("doc_values", false)
                        .endObject()
                    .endObject()
                .endObject()
            .endObject();

            return builder;
        } catch (IOException ioe) {
            throw new UncheckedIOException("Could not render enrich mapping", ioe);
        }
    }

    private void prepareAndCreateEnrichIndex() {
        long nowTimestamp = nowSupplier.getAsLong();
        String enrichIndexName = getEnrichIndexBase(policyName) + "-" + nowTimestamp;
        Settings enrichIndexSettings = Settings.builder()
            .put("index.auto_expand_replicas", "0-all")
            .build();
        CreateIndexRequest createEnrichIndexRequest = new CreateIndexRequest(enrichIndexName, enrichIndexSettings);
        createEnrichIndexRequest.mapping(MapperService.SINGLE_MAPPING_NAME, resolveEnrichMapping(policy));
        logger.debug("Policy [{}]: Creating new enrich index [{}]", policyName, enrichIndexName);
        client.admin().indices().create(createEnrichIndexRequest, new ActionListener<CreateIndexResponse>() {
            @Override
            public void onResponse(CreateIndexResponse createIndexResponse) {
                transferDataToEnrichIndex(enrichIndexName);
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }

    private void transferDataToEnrichIndex(final String destinationIndexName) {
        logger.debug("Policy [{}]: Transferring source data to new enrich index [{}]", policyName, destinationIndexName);
        // Filter down the source fields to just the ones required by the policy
        final Set<String> retainFields = new HashSet<>();
        retainFields.add(policy.getEnrichKey());
        retainFields.addAll(policy.getEnrichValues());
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.fetchSource(retainFields.toArray(new String[0]), new String[0]);
        if (policy.getQuery() != null) {
            searchSourceBuilder.query(QueryBuilders.wrapperQuery(policy.getQuery().getQuery()));
        }
        ReindexRequest reindexRequest = new ReindexRequest()
            .setDestIndex(destinationIndexName)
            .setSourceIndices(policy.getIndexPattern());
        reindexRequest.getSearchRequest().source(searchSourceBuilder);
        reindexRequest.getDestination().source(new BytesArray(new byte[0]), XContentType.SMILE);
        client.execute(ReindexAction.INSTANCE, reindexRequest, new ActionListener<BulkByScrollResponse>() {
            @Override
            public void onResponse(BulkByScrollResponse bulkByScrollResponse) {
                // Do we want to fail the request if there were failures during the reindex process?
                if (bulkByScrollResponse.getBulkFailures().size() > 0) {
                    listener.onFailure(new ElasticsearchException("Encountered bulk failures during reindex process"));
                } else if (bulkByScrollResponse.getSearchFailures().size() > 0) {
                    listener.onFailure(new ElasticsearchException("Encountered search failures during reindex process"));
                } else {
                    logger.info("Policy [{}]: Transferred [{}] documents to enrich index [{}]", policyName,
                        bulkByScrollResponse.getCreated(), destinationIndexName);
                    refreshEnrichIndex(destinationIndexName);
                }
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }

    private void refreshEnrichIndex(final String destinationIndexName) {
        logger.debug("Policy [{}]: Refreshing newly created enrich index [{}]", policyName, destinationIndexName);
        client.admin().indices().refresh(new RefreshRequest(destinationIndexName), new ActionListener<RefreshResponse>() {
            @Override
            public void onResponse(RefreshResponse refreshResponse) {
                updateEnrichPolicyAlias(destinationIndexName);
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }

    private void updateEnrichPolicyAlias(final String destinationIndexName) {
        String enrichIndexBase = getEnrichIndexBase(policyName);
        logger.debug("Policy [{}]: Promoting new enrich index [{}] to alias [{}]", policyName, destinationIndexName, enrichIndexBase);
        GetAliasesRequest aliasRequest = new GetAliasesRequest(enrichIndexBase);
        String[] concreteIndices = indexNameExpressionResolver.concreteIndexNames(clusterService.state(), aliasRequest);
        ImmutableOpenMap<String, List<AliasMetaData>> aliases =
            clusterService.state().metaData().findAliases(aliasRequest, concreteIndices);
        IndicesAliasesRequest aliasToggleRequest = new IndicesAliasesRequest();
        String[] indices = aliases.keys().toArray(String.class);
        if (indices.length > 0) {
            aliasToggleRequest.addAliasAction(IndicesAliasesRequest.AliasActions.remove().indices(indices).alias(enrichIndexBase));
        }
        aliasToggleRequest.addAliasAction(IndicesAliasesRequest.AliasActions.add().index(destinationIndexName).alias(enrichIndexBase));
        client.admin().indices().aliases(aliasToggleRequest, new ActionListener<AcknowledgedResponse>() {
            @Override
            public void onResponse(AcknowledgedResponse acknowledgedResponse) {
                logger.info("Policy [{}]: Policy execution complete", policyName);
                listener.onResponse(new PolicyExecutionResult(true));
            }

            @Override
            public void onFailure(Exception e) {
                listener.onFailure(e);
            }
        });
    }
}
