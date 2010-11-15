/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
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

package org.elasticsearch.cluster.metadata;

import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ProcessedClusterStateUpdateTask;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.allocation.RoutingAllocation;
import org.elasticsearch.cluster.routing.allocation.ShardsAllocation;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.collect.Maps;
import org.elasticsearch.common.collect.Sets;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.compress.CompressedString;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.service.IndexService;
import org.elasticsearch.indices.IndexAlreadyExistsException;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.indices.InvalidIndexNameException;
import org.elasticsearch.river.RiverIndexName;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.cluster.ClusterState.*;
import static org.elasticsearch.cluster.metadata.IndexMetaData.*;
import static org.elasticsearch.cluster.metadata.MetaData.*;
import static org.elasticsearch.common.settings.ImmutableSettings.*;

/**
 * @author kimchy (shay.banon)
 */
public class MetaDataCreateIndexService extends AbstractComponent {

    private final Environment environment;

    private final ClusterService clusterService;

    private final IndicesService indicesService;

    private final ShardsAllocation shardsAllocation;

    private final String riverIndexName;

    @Inject public MetaDataCreateIndexService(Settings settings, Environment environment, ClusterService clusterService, IndicesService indicesService,
                                              ShardsAllocation shardsAllocation, @RiverIndexName String riverIndexName) {
        super(settings);
        this.environment = environment;
        this.clusterService = clusterService;
        this.indicesService = indicesService;
        this.shardsAllocation = shardsAllocation;
        this.riverIndexName = riverIndexName;
    }

    public void createIndex(final Request request, final Listener listener) {
        ImmutableSettings.Builder updatedSettingsBuilder = ImmutableSettings.settingsBuilder();
        for (Map.Entry<String, String> entry : request.settings.getAsMap().entrySet()) {
            if (!entry.getKey().startsWith("index.")) {
                updatedSettingsBuilder.put("index." + entry.getKey(), entry.getValue());
            } else {
                updatedSettingsBuilder.put(entry.getKey(), entry.getValue());
            }
        }
        request.settings(updatedSettingsBuilder.build());

        clusterService.submitStateUpdateTask("create-index [" + request.index + "], cause [" + request.cause + "]", new ProcessedClusterStateUpdateTask() {
            @Override public ClusterState execute(ClusterState currentState) {
                try {
                    if (currentState.routingTable().hasIndex(request.index)) {
                        listener.onFailure(new IndexAlreadyExistsException(new Index(request.index)));
                        return currentState;
                    }
                    if (currentState.metaData().hasIndex(request.index)) {
                        listener.onFailure(new IndexAlreadyExistsException(new Index(request.index)));
                        return currentState;
                    }
                    if (request.index.contains(" ")) {
                        listener.onFailure(new InvalidIndexNameException(new Index(request.index), request.index, "must not contain whitespace"));
                        return currentState;
                    }
                    if (request.index.contains(",")) {
                        listener.onFailure(new InvalidIndexNameException(new Index(request.index), request.index, "must not contain ',"));
                        return currentState;
                    }
                    if (request.index.contains("#")) {
                        listener.onFailure(new InvalidIndexNameException(new Index(request.index), request.index, "must not contain '#"));
                        return currentState;
                    }
                    if (!request.index.equals(riverIndexName) && request.index.charAt(0) == '_') {
                        listener.onFailure(new InvalidIndexNameException(new Index(request.index), request.index, "must not start with '_'"));
                        return currentState;
                    }
                    if (!request.index.toLowerCase().equals(request.index)) {
                        listener.onFailure(new InvalidIndexNameException(new Index(request.index), request.index, "must be lowercase"));
                        return currentState;
                    }
                    if (!Strings.validFileName(request.index)) {
                        listener.onFailure(new InvalidIndexNameException(new Index(request.index), request.index, "must not contain the following characters " + Strings.INVALID_FILENAME_CHARS));
                        return currentState;
                    }
                    if (currentState.metaData().aliases().contains(request.index)) {
                        listener.onFailure(new InvalidIndexNameException(new Index(request.index), request.index, "an alias with the same name already exists"));
                        return currentState;
                    }

                    // add to the mappings files that exists within the config/mappings location
                    Map<String, CompressedString> mappings = Maps.newHashMap();
                    File mappingsDir = new File(environment.configFile(), "mappings");
                    if (mappingsDir.exists() && mappingsDir.isDirectory()) {
                        File defaultMappingsDir = new File(mappingsDir, "_default");
                        if (defaultMappingsDir.exists() && defaultMappingsDir.isDirectory()) {
                            addMappings(mappings, defaultMappingsDir);
                        }
                        File indexMappingsDir = new File(mappingsDir, request.index);
                        if (indexMappingsDir.exists() && indexMappingsDir.isDirectory()) {
                            addMappings(mappings, indexMappingsDir);
                        }
                    }

                    // put this last so index level mappings can override default mappings
                    for (Map.Entry<String, String> entry : request.mappings.entrySet()) {
                        mappings.put(entry.getKey(), new CompressedString(entry.getValue()));
                    }

                    ImmutableSettings.Builder indexSettingsBuilder = settingsBuilder().put(request.settings);
                    if (request.settings.get(SETTING_NUMBER_OF_SHARDS) == null) {
                        if (request.index.equals(riverIndexName)) {
                            indexSettingsBuilder.put(SETTING_NUMBER_OF_SHARDS, settings.getAsInt(SETTING_NUMBER_OF_SHARDS, 1));
                        } else {
                            indexSettingsBuilder.put(SETTING_NUMBER_OF_SHARDS, settings.getAsInt(SETTING_NUMBER_OF_SHARDS, 5));
                        }
                    }
                    if (request.settings.get(SETTING_NUMBER_OF_REPLICAS) == null) {
                        if (request.index.equals(riverIndexName)) {
                            indexSettingsBuilder.put(SETTING_NUMBER_OF_REPLICAS, settings.getAsInt(SETTING_NUMBER_OF_REPLICAS, 1));
                        } else {
                            indexSettingsBuilder.put(SETTING_NUMBER_OF_REPLICAS, settings.getAsInt(SETTING_NUMBER_OF_REPLICAS, 1));
                        }
                    }
                    Settings actualIndexSettings = indexSettingsBuilder.build();

                    // create the index here (on the master) to validate it can be created, as well as adding the mapping
                    indicesService.createIndex(request.index, actualIndexSettings, clusterService.state().nodes().localNode().id());
                    // now add the mappings
                    IndexService indexService = indicesService.indexServiceSafe(request.index);
                    MapperService mapperService = indexService.mapperService();
                    for (Map.Entry<String, CompressedString> entry : mappings.entrySet()) {
                        try {
                            mapperService.add(entry.getKey(), entry.getValue().string());
                        } catch (Exception e) {
                            indicesService.deleteIndex(request.index);
                            throw new MapperParsingException("mapping [" + entry.getKey() + "]", e);
                        }
                    }
                    // now, update the mappings with the actual source
                    Map<String, MappingMetaData> mappingsMetaData = Maps.newHashMap();
                    for (DocumentMapper mapper : mapperService) {
                        MappingMetaData mappingMd = new MappingMetaData(mapper);
                        mappingsMetaData.put(mapper.type(), mappingMd);
                    }

                    final IndexMetaData.Builder indexMetaDataBuilder = newIndexMetaDataBuilder(request.index).settings(actualIndexSettings);
                    for (MappingMetaData mappingMd : mappingsMetaData.values()) {
                        indexMetaDataBuilder.putMapping(mappingMd);
                    }
                    indexMetaDataBuilder.state(request.state);
                    final IndexMetaData indexMetaData = indexMetaDataBuilder.build();

                    MetaData newMetaData = newMetaDataBuilder()
                            .metaData(currentState.metaData())
                            .put(indexMetaData)
                            .build();

                    logger.info("[{}] creating index, cause [{}], shards [{}]/[{}], mappings {}", request.index, request.cause, indexMetaData.numberOfShards(), indexMetaData.numberOfReplicas(), mappings.keySet());

                    ClusterBlocks.Builder blocks = ClusterBlocks.builder().blocks(currentState.blocks());
                    if (!request.blocks.isEmpty()) {
                        for (ClusterBlock block : request.blocks) {
                            blocks.addIndexBlock(request.index, block);
                        }
                    }
                    if (request.state == State.CLOSE) {
                        blocks.addIndexBlock(request.index, MetaDataStateIndexService.INDEX_CLOSED_BLOCK);
                    }

                    return newClusterStateBuilder().state(currentState).blocks(blocks).metaData(newMetaData).build();
                } catch (Exception e) {
                    listener.onFailure(e);
                    return currentState;
                }
            }

            @Override public void clusterStateProcessed(ClusterState clusterState) {
                if (request.state == State.CLOSE) { // no need to do shard allocated when closed...
                    listener.onResponse(new Response(true, clusterState.metaData().index(request.index)));
                    return;
                }
                clusterService.submitStateUpdateTask("reroute after index [" + request.index + "] creation", new ProcessedClusterStateUpdateTask() {
                    @Override public ClusterState execute(ClusterState currentState) {
                        RoutingTable.Builder routingTableBuilder = RoutingTable.builder().routingTable(currentState.routingTable());
                        IndexRoutingTable.Builder indexRoutingBuilder = new IndexRoutingTable.Builder(request.index)
                                .initializeEmpty(currentState.metaData().index(request.index));
                        routingTableBuilder.add(indexRoutingBuilder);
                        RoutingAllocation.Result routingResult = shardsAllocation.reroute(newClusterStateBuilder().state(currentState).routingTable(routingTableBuilder).build());
                        return newClusterStateBuilder().state(currentState).routingResult(routingResult).build();
                    }

                    @Override public void clusterStateProcessed(ClusterState clusterState) {
                        logger.info("[{}] created and added to cluster_state", request.index);
                        listener.onResponse(new Response(true, clusterState.metaData().index(request.index)));
                    }
                });
            }
        });
    }

    private void addMappings(Map<String, CompressedString> mappings, File mappingsDir) {
        File[] mappingsFiles = mappingsDir.listFiles();
        for (File mappingFile : mappingsFiles) {
            String fileNameNoSuffix = mappingFile.getName().substring(0, mappingFile.getName().lastIndexOf('.'));
            if (mappings.containsKey(fileNameNoSuffix)) {
                // if we have the mapping defined, ignore it
                continue;
            }
            try {
                mappings.put(fileNameNoSuffix, new CompressedString(Streams.copyToString(new FileReader(mappingFile))));
            } catch (IOException e) {
                logger.warn("failed to read mapping [" + fileNameNoSuffix + "] from location [" + mappingFile + "], ignoring...", e);
            }
        }
    }

    public static interface Listener {

        void onResponse(Response response);

        void onFailure(Throwable t);
    }

    public static class Request {

        final String cause;

        final String index;

        State state = State.OPEN;

        Settings settings = ImmutableSettings.Builder.EMPTY_SETTINGS;

        Map<String, String> mappings = Maps.newHashMap();

        TimeValue timeout = TimeValue.timeValueSeconds(5);

        Set<ClusterBlock> blocks = Sets.newHashSet();

        public Request(String cause, String index) {
            this.cause = cause;
            this.index = index;
        }

        public Request settings(Settings settings) {
            this.settings = settings;
            return this;
        }

        public Request mappings(Map<String, String> mappings) {
            this.mappings.putAll(mappings);
            return this;
        }

        public Request mappingsMetaData(Map<String, MappingMetaData> mappings) throws IOException {
            for (Map.Entry<String, MappingMetaData> entry : mappings.entrySet()) {
                this.mappings.put(entry.getKey(), entry.getValue().source().string());
            }
            return this;
        }

        public Request mappingsCompressed(Map<String, CompressedString> mappings) throws IOException {
            for (Map.Entry<String, CompressedString> entry : mappings.entrySet()) {
                this.mappings.put(entry.getKey(), entry.getValue().string());
            }
            return this;
        }

        public Request blocks(Set<ClusterBlock> blocks) {
            this.blocks.addAll(blocks);
            return this;
        }

        public Request state(State state) {
            this.state = state;
            return this;
        }

        public Request timeout(TimeValue timeout) {
            this.timeout = timeout;
            return this;
        }
    }

    public static class Response {
        private final boolean acknowledged;
        private final IndexMetaData indexMetaData;

        public Response(boolean acknowledged, IndexMetaData indexMetaData) {
            this.acknowledged = acknowledged;
            this.indexMetaData = indexMetaData;
        }

        public boolean acknowledged() {
            return acknowledged;
        }

        public IndexMetaData indexMetaData() {
            return indexMetaData;
        }
    }
}
