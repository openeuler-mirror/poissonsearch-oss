/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.elasticsearch.index.rankeval;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.io.stream.NamedWriteable;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

public class RankEvalPlugin extends Plugin implements ActionPlugin {

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        return Arrays.asList(new ActionHandler<>(RankEvalAction.INSTANCE, TransportRankEvalAction.class));
    }


    @Override
    public List<RestHandler> getRestHandlers(Settings settings, RestController restController, ClusterSettings clusterSettings,
            IndexScopedSettings indexScopedSettings, SettingsFilter settingsFilter, IndexNameExpressionResolver indexNameExpressionResolver,
            Supplier<DiscoveryNodes> nodesInCluster) {
        return Arrays.asList(new RestRankEvalAction(settings, restController));
    }

    /**
     * Returns parsers for {@link NamedWriteable} this plugin will use over the transport protocol.
     * @see NamedWriteableRegistry
     */
    @Override
    public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
        List<NamedWriteableRegistry.Entry> namedWriteables = new ArrayList<>();
        namedWriteables.add(new NamedWriteableRegistry.Entry(RankedListQualityMetric.class, Precision.NAME, Precision::new));
        namedWriteables.add(new NamedWriteableRegistry.Entry(RankedListQualityMetric.class, ReciprocalRank.NAME, ReciprocalRank::new));
        namedWriteables.add(new NamedWriteableRegistry.Entry(RankedListQualityMetric.class, DiscountedCumulativeGain.NAME,
                DiscountedCumulativeGain::new));
        namedWriteables.add(new NamedWriteableRegistry.Entry(MetricDetails.class, Precision.NAME,
                Precision.Breakdown::new));
        namedWriteables.add(new NamedWriteableRegistry.Entry(MetricDetails.class, ReciprocalRank.NAME,
                ReciprocalRank.Breakdown::new));
        return namedWriteables;
    }
}
