/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.deprecation;

import com.carrotsearch.hppc.cursors.ObjectCursor;
import org.elasticsearch.Version;
import org.elasticsearch.action.admin.cluster.node.info.NodeInfo;
import org.elasticsearch.action.admin.cluster.node.stats.NodeStats;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.metadata.MappingMetaData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Class containing all the cluster, node, and index deprecation checks that will be served
 * by the {@link DeprecationInfoAction}.
 */
public class DeprecationChecks {

    private DeprecationChecks() {
    }

    static List<Function<ClusterState, DeprecationIssue>> CLUSTER_SETTINGS_CHECKS =
        Collections.unmodifiableList(Arrays.asList(
            // STUB
        ));

    static List<BiFunction<List<NodeInfo>, List<NodeStats>, DeprecationIssue>> NODE_SETTINGS_CHECKS =
        Collections.unmodifiableList(Arrays.asList(
            // STUB
        ));

    @SuppressWarnings("unchecked")
    static List<Function<IndexMetaData, DeprecationIssue>> INDEX_SETTINGS_CHECKS =
        Collections.unmodifiableList(Arrays.asList(
            IndexDeprecationChecks::baseSimilarityDefinedCheck,
            IndexDeprecationChecks::coercionCheck,
            IndexDeprecationChecks::dynamicTemplateWithMatchMappingTypeCheck,
            IndexDeprecationChecks::indexSharedFileSystemCheck,
            IndexDeprecationChecks::indexStoreTypeCheck,
            IndexDeprecationChecks::storeThrottleSettingsCheck));

}
