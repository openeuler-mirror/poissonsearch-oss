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

package org.elasticsearch.discovery.gce;

import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.discovery.DiscoverySettings;
import org.elasticsearch.discovery.zen.ZenDiscovery;
import org.elasticsearch.discovery.zen.elect.ElectMasterService;
import org.elasticsearch.discovery.zen.ping.ZenPingService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.util.Collections;
import java.util.List;

/**
 *
 */
public class GceDiscovery extends ZenDiscovery {

    public static final String GCE = "gce";

    /**
     * discovery.gce.tags: The gce discovery can filter machines to include in the cluster based on tags.
     */
    public static final Setting<List<String>> TAGS_SETTING =
        Setting.listSetting("discovery.gce.tags", Collections.emptyList(), s -> s, false, Setting.Scope.CLUSTER);

    @Inject
    public GceDiscovery(Settings settings, ClusterName clusterName, ThreadPool threadPool, TransportService transportService,
                        ClusterService clusterService, ClusterSettings clusterSettings, ZenPingService pingService,
                        DiscoverySettings discoverySettings,
                        ElectMasterService electMasterService) {
        super(settings, clusterName, threadPool, transportService, clusterService, clusterSettings,
                pingService, electMasterService, discoverySettings);
    }
}
