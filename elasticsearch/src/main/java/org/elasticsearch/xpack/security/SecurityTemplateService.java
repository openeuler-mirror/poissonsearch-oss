/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateResponse;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.IndexTemplateMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.xpack.security.authc.esnative.NativeRealmMigrator;
import org.elasticsearch.xpack.template.TemplateUtils;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * SecurityTemplateService is responsible for adding the template needed for the
 * {@code .security} administrative index.
 */
public class SecurityTemplateService extends AbstractComponent implements ClusterStateListener {

    public static final String SECURITY_INDEX_NAME = ".security";
    public static final String SECURITY_TEMPLATE_NAME = "security-index-template";
    private static final String SECURITY_VERSION_STRING = "security-version";
    static final String SECURITY_INDEX_TEMPLATE_VERSION_PATTERN = Pattern.quote("${security.template.version}");
    static final Version MIN_READ_VERSION = Version.V_5_0_0;

    enum UpgradeState {
        NOT_STARTED, IN_PROGRESS, COMPLETE, FAILED
    }

    private final InternalClient client;
    final AtomicBoolean templateCreationPending = new AtomicBoolean(false);
    final AtomicBoolean updateMappingPending = new AtomicBoolean(false);
    final AtomicReference upgradeDataState = new AtomicReference<>(UpgradeState.NOT_STARTED);
    private final NativeRealmMigrator nativeRealmMigrator;

    public SecurityTemplateService(Settings settings, InternalClient client, NativeRealmMigrator nativeRealmMigrator) {
        super(settings);
        this.client = client;
        this.nativeRealmMigrator = nativeRealmMigrator;
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (event.localNodeMaster() == false) {
            return;
        }
        ClusterState state = event.state();
        if (state.blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            // wait until the gateway has recovered from disk, otherwise we think may not have .security-audit-
            // but they may not have been restored from the cluster state on disk
            logger.debug("template service waiting until state has been recovered");
            return;
        }
        if (securityTemplateExistsAndIsUpToDate(state, logger) == false) {
            updateSecurityTemplate();
        }
        // make sure mapping is up to date
        if (state.metaData().getIndices() != null) {
            if (securityIndexMappingUpToDate(state, logger) == false) {
                if (securityIndexAvailable(state, logger)) {
                    upgradeSecurityData(state, this::updateSecurityMapping);
                }
            }
        }
    }

    private boolean securityIndexAvailable(ClusterState state, Logger logger) {
        final IndexRoutingTable routingTable = getSecurityIndexRoutingTable(state);
        if (routingTable == null) {
            throw new IllegalStateException("Security index does not exist");
        }
        if (routingTable.allPrimaryShardsActive() == false) {
            logger.debug("Security index is not yet active");
            return false;
        }
        return true;
    }

    private void updateSecurityTemplate() {
        // only put the template if this is not already in progress
        if (templateCreationPending.compareAndSet(false, true)) {
            putSecurityTemplate();
        }
    }

    private boolean upgradeSecurityData(ClusterState state, Runnable andThen) {
        // only update the data if this is not already in progress
        if (upgradeDataState.compareAndSet(UpgradeState.NOT_STARTED, UpgradeState.IN_PROGRESS) ) {
            final Version previousVersion = oldestSecurityIndexMappingVersion(state, logger);
            nativeRealmMigrator.performUpgrade(previousVersion, new ActionListener<Boolean>() {

                @Override
                public void onResponse(Boolean upgraded) {
                    upgradeDataState.set(UpgradeState.COMPLETE);
                    andThen.run();
                }

                @Override
                public void onFailure(Exception e) {
                    upgradeDataState.set(UpgradeState.FAILED);
                    logger.error((Supplier<?>) () -> new ParameterizedMessage("failed to upgrade security data from version [{}] ",
                            previousVersion), e);
                }
            });
            return true;
        } else {
            andThen.run();
            return false;
        }
    }


    private void updateSecurityMapping() {
        // only update the mapping if this is not already in progress
        if (updateMappingPending.compareAndSet(false, true) ) {
            putSecurityMappings();
        }
    }

    private void putSecurityMappings() {
        String template = TemplateUtils.loadTemplate("/" + SECURITY_TEMPLATE_NAME + ".json", Version.CURRENT.toString()
                , SECURITY_INDEX_TEMPLATE_VERSION_PATTERN);
        Map<String, Object> typeMappingMap;
        try {
            typeMappingMap = XContentHelper.convertToMap(JsonXContent.jsonXContent, template, false);
        } catch (ElasticsearchParseException e) {
            updateMappingPending.set(false);
            logger.error("failed to parse the security index template", e);
            throw new ElasticsearchException("failed to parse the security index template", e);
        }

        // here go over all types found in the template and update them
        // we need to wait for all types
        final Map<String, PutMappingResponse> updateResults = ConcurrentCollections.newConcurrentMap();
        @SuppressWarnings("unchecked")
        Map<String, Object> typeMappings = (Map<String, Object>) typeMappingMap.get("mappings");
        int expectedResults = typeMappings.size();
        for (String type : typeMappings.keySet()) {
            // get the mappings from the template definition
            @SuppressWarnings("unchecked")
            Map<String, Object> typeMapping = (Map<String, Object>) typeMappings.get(type);
            // update the mapping
            putSecurityMapping(updateResults, expectedResults, type, typeMapping);
        }
    }

    private void putSecurityMapping(final Map<String, PutMappingResponse> updateResults, int expectedResults,
                                    final String type, Map<String, Object> typeMapping) {
        logger.debug("updating mapping of the security index for type [{}]", type);
        PutMappingRequest putMappingRequest = client.admin().indices()
                .preparePutMapping(SECURITY_INDEX_NAME).setSource(typeMapping).setType(type).request();
        client.admin().indices().putMapping(putMappingRequest, new ActionListener<PutMappingResponse>() {
            @Override
            public void onResponse(PutMappingResponse putMappingResponse) {
                if (putMappingResponse.isAcknowledged() == false) {
                    updateMappingPending.set(false);
                    throw new ElasticsearchException("update mapping for [{}] security index " +
                            "was not acknowledged", type);
                } else {
                    updateResults.put(type, putMappingResponse);
                    if (updateResults.size() == expectedResults) {
                        updateMappingPending.set(false);
                    }
                }
            }

            @Override
            public void onFailure(Exception e) {
                updateMappingPending.set(false);
                logger.warn((Supplier<?>) () -> new ParameterizedMessage("failed to update mapping for [{}] on security index", type), e);
            }
        });
    }

    private void putSecurityTemplate() {
        logger.debug("putting the security index template");
        String template = TemplateUtils.loadTemplate("/" + SECURITY_TEMPLATE_NAME + ".json", Version.CURRENT.toString()
                , SECURITY_INDEX_TEMPLATE_VERSION_PATTERN);

        PutIndexTemplateRequest putTemplateRequest = client.admin().indices()
                .preparePutTemplate(SECURITY_TEMPLATE_NAME).setSource(template).request();
        client.admin().indices().putTemplate(putTemplateRequest, new ActionListener<PutIndexTemplateResponse>() {
            @Override
            public void onResponse(PutIndexTemplateResponse putIndexTemplateResponse) {
                templateCreationPending.set(false);
                if (putIndexTemplateResponse.isAcknowledged() == false) {
                    throw new ElasticsearchException("put template for security index was not acknowledged");
                }
            }

            @Override
            public void onFailure(Exception e) {
                templateCreationPending.set(false);
                logger.warn("failed to put security index template", e);
            }
        });
    }

    static boolean securityIndexMappingUpToDate(ClusterState clusterState, Logger logger) {
        return securityIndexMappingVersionMatches(clusterState, logger, Version.CURRENT::equals);
    }

    static boolean securityIndexMappingVersionMatches(ClusterState clusterState, Logger logger, Predicate<Version> predicate) {
        return securityIndexMappingVersions(clusterState, logger).stream().allMatch(predicate);
    }

    public static Version oldestSecurityIndexMappingVersion(ClusterState clusterState, Logger logger) {
        final Set<Version> versions = securityIndexMappingVersions(clusterState, logger);
        return versions.stream().min(Version::compareTo).orElse(null);
    }

    private static Set<Version> securityIndexMappingVersions(ClusterState clusterState, Logger logger) {
        Set<Version> versions = new HashSet<>();
        IndexMetaData indexMetaData = clusterState.metaData().getIndices().get(SECURITY_INDEX_NAME);
        if (indexMetaData != null) {
            for (Object object : indexMetaData.getMappings().values().toArray()) {
                MappingMetaData mappingMetaData = (MappingMetaData) object;
                if (mappingMetaData.type().equals(MapperService.DEFAULT_MAPPING)) {
                    continue;
                }
                versions.add(readMappingVersion(mappingMetaData, logger));
            }
        }
        return versions;
    }

    private static Version readMappingVersion(MappingMetaData mappingMetaData, Logger logger) {
        try {
            Map<String, Object> meta = (Map<String, Object>) mappingMetaData.sourceAsMap().get("_meta");
            if (meta == null) {
                // something pre-5.0, but we don't know what. Use 2.3.0 as a placeholder for "old"
                return Version.V_2_3_0;
            }
            return Version.fromString((String) meta.get(SECURITY_VERSION_STRING));
        } catch (IOException e) {
            logger.error("Cannot parse the mapping for security index.", e);
            throw new ElasticsearchException("Cannot parse the mapping for security index.", e);
        }
    }

    static boolean securityTemplateExistsAndIsUpToDate(ClusterState state, Logger logger) {
        return securityTemplateExistsAndVersionMatches(state, logger, Version.CURRENT::equals);
    }

    static boolean securityTemplateExistsAndVersionMatches(ClusterState state, Logger logger, Predicate<Version> predicate) {
        IndexTemplateMetaData templateMeta = state.metaData().templates().get(SECURITY_TEMPLATE_NAME);
        if (templateMeta == null) {
            return false;
        }
        ImmutableOpenMap<String, CompressedXContent> mappings = templateMeta.getMappings();
        // check all mappings contain correct version in _meta
        // we have to parse the source here which is annoying
        for (Object typeMapping : mappings.values().toArray()) {
            CompressedXContent typeMappingXContent = (CompressedXContent) typeMapping;
            try  {
                Map<String, Object> typeMappingMap = XContentHelper.convertToMap(new BytesArray(typeMappingXContent.uncompressed()), false)
                        .v2();
                // should always contain one entry with key = typename
                assert (typeMappingMap.size() == 1);
                String key = typeMappingMap.keySet().iterator().next();
                // get the actual mapping entries
                @SuppressWarnings("unchecked")
                Map<String, Object> mappingMap = (Map<String, Object>) typeMappingMap.get(key);
                if (containsCorrectVersion(mappingMap, predicate) == false) {
                    return false;
                }
            } catch (ElasticsearchParseException e) {
                logger.error("Cannot parse the template for security index.", e);
                throw new IllegalStateException("Cannot parse the template for security index.", e);
            }
        }
        return true;
    }

    private static boolean containsCorrectVersion(Map<String, Object> typeMappingMap, Predicate<Version> predicate) {
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) typeMappingMap.get("_meta");
        if (meta == null) {
            // pre 5.0, cannot be up to date
            return false;
        }
        return predicate.test(Version.fromString((String) meta.get(SECURITY_VERSION_STRING)));
    }

    /**
     * Returns the routing-table for the security index, or <code>null</code> if the security index does not exist.
     */
    public static IndexRoutingTable getSecurityIndexRoutingTable(ClusterState clusterState) {
        IndexMetaData metaData = clusterState.metaData().index(SECURITY_INDEX_NAME);
        if (metaData == null) {
            return null;
        } else {
            return clusterState.routingTable().index(SECURITY_INDEX_NAME);
        }
    }

    public static boolean securityIndexMappingAndTemplateUpToDate(ClusterState clusterState, Logger logger) {
        if (securityTemplateExistsAndIsUpToDate(clusterState, logger) == false) {
            logger.debug("security template [{}] does not exist or is not up to date, so service cannot start",
                    SecurityTemplateService.SECURITY_TEMPLATE_NAME);
            return false;
        }
        if (SecurityTemplateService.securityIndexMappingUpToDate(clusterState, logger) == false) {
            logger.debug("mapping for security index not up to date, so service cannot start");
            return false;
        }
        return true;
    }

    public static boolean securityIndexMappingAndTemplateSufficientToRead(ClusterState clusterState, Logger logger) {
        if (securityTemplateExistsAndVersionMatches(clusterState, logger, MIN_READ_VERSION::onOrBefore) == false) {
            logger.debug("security template [{}] does not exist or is not up to date, so service cannot start",
                    SecurityTemplateService.SECURITY_TEMPLATE_NAME);
            return false;
        }
        if (securityIndexMappingVersionMatches(clusterState, logger, MIN_READ_VERSION::onOrBefore) == false) {
            logger.debug("mapping for security index not up to date, so service cannot start");
            return false;
        }
        return true;
    }
}
