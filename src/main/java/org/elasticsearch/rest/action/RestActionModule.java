/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.rest.action;

import com.google.common.collect.Lists;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.action.admin.cluster.health.RestClusterHealthAction;
import org.elasticsearch.rest.action.admin.cluster.node.hotthreads.RestNodesHotThreadsAction;
import org.elasticsearch.rest.action.admin.cluster.node.info.RestNodesInfoAction;
import org.elasticsearch.rest.action.admin.cluster.node.restart.RestNodesRestartAction;
import org.elasticsearch.rest.action.admin.cluster.node.shutdown.RestNodesShutdownAction;
import org.elasticsearch.rest.action.admin.cluster.node.stats.RestNodesStatsAction;
import org.elasticsearch.rest.action.admin.cluster.reroute.RestClusterRerouteAction;
import org.elasticsearch.rest.action.admin.cluster.settings.RestClusterGetSettingsAction;
import org.elasticsearch.rest.action.admin.cluster.settings.RestClusterUpdateSettingsAction;
import org.elasticsearch.rest.action.admin.cluster.shards.RestClusterSearchShardsAction;
import org.elasticsearch.rest.action.admin.cluster.state.RestClusterStateAction;
import org.elasticsearch.rest.action.admin.indices.alias.RestGetIndicesAliasesAction;
import org.elasticsearch.rest.action.admin.indices.alias.RestIndicesAliasesAction;
import org.elasticsearch.rest.action.admin.indices.analyze.RestAnalyzeAction;
import org.elasticsearch.rest.action.admin.indices.cache.clear.RestClearIndicesCacheAction;
import org.elasticsearch.rest.action.admin.indices.close.RestCloseIndexAction;
import org.elasticsearch.rest.action.admin.indices.create.RestCreateIndexAction;
import org.elasticsearch.rest.action.admin.indices.delete.RestDeleteIndexAction;
import org.elasticsearch.rest.action.admin.indices.exists.indices.RestIndicesExistsAction;
import org.elasticsearch.rest.action.admin.indices.exists.types.RestTypesExistsAction;
import org.elasticsearch.rest.action.admin.indices.flush.RestFlushAction;
import org.elasticsearch.rest.action.admin.indices.gateway.snapshot.RestGatewaySnapshotAction;
import org.elasticsearch.rest.action.admin.indices.mapping.delete.RestDeleteMappingAction;
import org.elasticsearch.rest.action.admin.indices.mapping.get.RestGetMappingAction;
import org.elasticsearch.rest.action.admin.indices.mapping.put.RestPutMappingAction;
import org.elasticsearch.rest.action.admin.indices.open.RestOpenIndexAction;
import org.elasticsearch.rest.action.admin.indices.optimize.RestOptimizeAction;
import org.elasticsearch.rest.action.admin.indices.refresh.RestRefreshAction;
import org.elasticsearch.rest.action.admin.indices.segments.RestIndicesSegmentsAction;
import org.elasticsearch.rest.action.admin.indices.settings.RestGetSettingsAction;
import org.elasticsearch.rest.action.admin.indices.settings.RestUpdateSettingsAction;
import org.elasticsearch.rest.action.admin.indices.stats.RestIndicesStatsAction;
import org.elasticsearch.rest.action.admin.indices.status.RestIndicesStatusAction;
import org.elasticsearch.rest.action.admin.indices.template.delete.RestDeleteIndexTemplateAction;
import org.elasticsearch.rest.action.admin.indices.template.get.RestGetIndexTemplateAction;
import org.elasticsearch.rest.action.admin.indices.template.put.RestPutIndexTemplateAction;
import org.elasticsearch.rest.action.admin.indices.validate.query.RestValidateQueryAction;
import org.elasticsearch.rest.action.admin.indices.warmer.delete.RestDeleteWarmerAction;
import org.elasticsearch.rest.action.admin.indices.warmer.get.RestGetWarmerAction;
import org.elasticsearch.rest.action.admin.indices.warmer.put.RestPutWarmerAction;
import org.elasticsearch.rest.action.bulk.RestBulkAction;
import org.elasticsearch.rest.action.count.RestCountAction;
import org.elasticsearch.rest.action.delete.RestDeleteAction;
import org.elasticsearch.rest.action.deletebyquery.RestDeleteByQueryAction;
import org.elasticsearch.rest.action.explain.RestExplainAction;
import org.elasticsearch.rest.action.get.RestGetAction;
import org.elasticsearch.rest.action.get.RestHeadAction;
import org.elasticsearch.rest.action.get.RestMultiGetAction;
import org.elasticsearch.rest.action.index.RestIndexAction;
import org.elasticsearch.rest.action.main.RestMainAction;
import org.elasticsearch.rest.action.mlt.RestMoreLikeThisAction;
import org.elasticsearch.rest.action.percolate.RestPercolateAction;
import org.elasticsearch.rest.action.search.RestMultiSearchAction;
import org.elasticsearch.rest.action.search.RestSearchAction;
import org.elasticsearch.rest.action.search.RestSearchScrollAction;
import org.elasticsearch.rest.action.update.RestUpdateAction;

import java.util.List;

/**
 *
 */
public class RestActionModule extends AbstractModule {
    private List<Class<? extends BaseRestHandler>> restPluginsActions = Lists.newArrayList();

    public RestActionModule(List<Class<? extends BaseRestHandler>> restPluginsActions) {
        this.restPluginsActions = restPluginsActions;
    }

    @Override
    protected void configure() {
        for (Class<? extends BaseRestHandler> restAction : restPluginsActions) {
            bind(restAction).asEagerSingleton();
        }

        bind(RestMainAction.class).asEagerSingleton();

        bind(RestNodesInfoAction.class).asEagerSingleton();
        bind(RestNodesStatsAction.class).asEagerSingleton();
        bind(RestNodesHotThreadsAction.class).asEagerSingleton();
        bind(RestNodesShutdownAction.class).asEagerSingleton();
        bind(RestNodesRestartAction.class).asEagerSingleton();
        bind(RestClusterStateAction.class).asEagerSingleton();
        bind(RestClusterHealthAction.class).asEagerSingleton();
        bind(RestClusterUpdateSettingsAction.class).asEagerSingleton();
        bind(RestClusterGetSettingsAction.class).asEagerSingleton();
        bind(RestClusterRerouteAction.class).asEagerSingleton();
        bind(RestClusterSearchShardsAction.class).asEagerSingleton();

        bind(RestIndicesExistsAction.class).asEagerSingleton();
        bind(RestTypesExistsAction.class).asEagerSingleton();
        bind(RestIndicesStatsAction.class).asEagerSingleton();
        bind(RestIndicesStatusAction.class).asEagerSingleton();
        bind(RestIndicesSegmentsAction.class).asEagerSingleton();
        bind(RestGetIndicesAliasesAction.class).asEagerSingleton();
        bind(RestIndicesAliasesAction.class).asEagerSingleton();
        bind(RestCreateIndexAction.class).asEagerSingleton();
        bind(RestDeleteIndexAction.class).asEagerSingleton();
        bind(RestCloseIndexAction.class).asEagerSingleton();
        bind(RestOpenIndexAction.class).asEagerSingleton();

        bind(RestUpdateSettingsAction.class).asEagerSingleton();
        bind(RestGetSettingsAction.class).asEagerSingleton();

        bind(RestAnalyzeAction.class).asEagerSingleton();
        bind(RestGetIndexTemplateAction.class).asEagerSingleton();
        bind(RestPutIndexTemplateAction.class).asEagerSingleton();
        bind(RestDeleteIndexTemplateAction.class).asEagerSingleton();

        bind(RestPutWarmerAction.class).asEagerSingleton();
        bind(RestDeleteWarmerAction.class).asEagerSingleton();
        bind(RestGetWarmerAction.class).asEagerSingleton();

        bind(RestPutMappingAction.class).asEagerSingleton();
        bind(RestDeleteMappingAction.class).asEagerSingleton();
        bind(RestGetMappingAction.class).asEagerSingleton();

        bind(RestGatewaySnapshotAction.class).asEagerSingleton();

        bind(RestRefreshAction.class).asEagerSingleton();
        bind(RestFlushAction.class).asEagerSingleton();
        bind(RestOptimizeAction.class).asEagerSingleton();
        bind(RestClearIndicesCacheAction.class).asEagerSingleton();

        bind(RestIndexAction.class).asEagerSingleton();
        bind(RestGetAction.class).asEagerSingleton();
        bind(RestHeadAction.class).asEagerSingleton();
        bind(RestMultiGetAction.class).asEagerSingleton();
        bind(RestDeleteAction.class).asEagerSingleton();
        bind(RestDeleteByQueryAction.class).asEagerSingleton();
        bind(RestCountAction.class).asEagerSingleton();
        bind(RestBulkAction.class).asEagerSingleton();
        bind(RestUpdateAction.class).asEagerSingleton();
        bind(RestPercolateAction.class).asEagerSingleton();

        bind(RestSearchAction.class).asEagerSingleton();
        bind(RestSearchScrollAction.class).asEagerSingleton();
        bind(RestMultiSearchAction.class).asEagerSingleton();

        bind(RestValidateQueryAction.class).asEagerSingleton();

        bind(RestMoreLikeThisAction.class).asEagerSingleton();

        bind(RestExplainAction.class).asEagerSingleton();
    }
}
