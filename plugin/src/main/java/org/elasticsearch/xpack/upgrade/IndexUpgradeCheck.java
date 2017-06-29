/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.upgrade;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.reindex.BulkByScrollResponse;
import org.elasticsearch.script.Script;
import org.elasticsearch.transport.TransportResponse;

import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Generic upgrade check applicable to all indices to be upgraded from the current version
 * to the next major version
 * <p>
 * The upgrade is performed in the following way:
 * <p>
 * - preUpgrade method is called
 * - reindex is performed
 * - postUpgrade is called if reindex was successful
 */
public class IndexUpgradeCheck<T> extends AbstractComponent {
    public static final int UPRADE_VERSION = 6;

    private final String name;
    private final BiFunction<IndexMetaData, Map<String, String>, UpgradeActionRequired> actionRequired;
    private final InternalIndexReindexer<T> reindexer;

    /**
     * Creates a new upgrade check
     *
     * @param name           - the name of the check
     * @param settings       - system settings
     * @param actionRequired - return true if they can work with the index with specified name
     * @param client         - client
     * @param clusterService - cluster service
     * @param types          - a list of types that the reindexing should be limited to
     * @param updateScript   - the upgrade script that should be used during reindexing
     */
    public IndexUpgradeCheck(String name, Settings settings,
                             BiFunction<IndexMetaData, Map<String, String>, UpgradeActionRequired> actionRequired,
                             Client client, ClusterService clusterService, String[] types, Script updateScript) {
        this(name, settings, actionRequired, client, clusterService, types, updateScript,
                listener -> listener.onResponse(null), (t, listener) -> listener.onResponse(TransportResponse.Empty.INSTANCE));
    }

    /**
     * Creates a new upgrade check
     *
     * @param name           - the name of the check
     * @param settings       - system settings
     * @param actionRequired - return true if they can work with the index with specified name
     * @param client         - client
     * @param clusterService - cluster service
     * @param types          - a list of types that the reindexing should be limited to
     * @param updateScript   - the upgrade script that should be used during reindexing
     * @param preUpgrade     - action that should be performed before upgrade
     * @param postUpgrade    - action that should be performed after upgrade
     */
    public IndexUpgradeCheck(String name, Settings settings,
                             BiFunction<IndexMetaData, Map<String, String>, UpgradeActionRequired> actionRequired,
                             Client client, ClusterService clusterService, String[] types, Script updateScript,
                             Consumer<ActionListener<T>> preUpgrade,
                             BiConsumer<T, ActionListener<TransportResponse.Empty>> postUpgrade) {
        super(settings);
        this.name = name;
        this.actionRequired = actionRequired;
        this.reindexer = new InternalIndexReindexer<>(client, clusterService, UPRADE_VERSION, updateScript, types, preUpgrade,
                postUpgrade);
    }

    /**
     * Returns the name of the check
     */
    public String getName() {
        return name;
    }

    /**
     * This method is called by Upgrade API to verify if upgrade or reindex for this index is required
     *
     * @param indexMetaData index metadata
     * @param params        additional user-specified parameters see {@link IndexUpgradeCheckFactory#supportedParams}
     * @return required action or UpgradeActionRequired.NOT_APPLICABLE if this check cannot be performed on the index
     */
    public UpgradeActionRequired actionRequired(IndexMetaData indexMetaData, Map<String, String> params) {
        return actionRequired.apply(indexMetaData, params);
    }

    /**
     * Perform the index upgrade
     *
     * @param indexMetaData index metadata
     * @param state         current cluster state
     * @param listener      the listener that should be called upon completion of the upgrade
     */
    public void upgrade(IndexMetaData indexMetaData, ClusterState state,
                        ActionListener<BulkByScrollResponse> listener) {
        reindexer.upgrade(indexMetaData.getIndex().getName(), state, listener);
    }
}
