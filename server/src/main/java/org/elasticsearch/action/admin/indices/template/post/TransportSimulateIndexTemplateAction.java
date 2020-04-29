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

package org.elasticsearch.action.admin.indices.template.post;

import org.elasticsearch.Version;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.master.TransportMasterNodeReadAction;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.block.ClusterBlockException;
import org.elasticsearch.cluster.block.ClusterBlockLevel;
import org.elasticsearch.cluster.metadata.AliasMetadata;
import org.elasticsearch.cluster.metadata.AliasValidator;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.IndexTemplateV2;
import org.elasticsearch.cluster.metadata.Metadata;
import org.elasticsearch.cluster.metadata.MetadataCreateIndexService;
import org.elasticsearch.cluster.metadata.MetadataIndexTemplateService;
import org.elasticsearch.cluster.metadata.Template;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.UUIDs;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.Closeable;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.elasticsearch.cluster.metadata.MetadataCreateIndexService.resolveV2Mappings;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.findConflictingV1Templates;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.findConflictingV2Templates;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.findV2Template;
import static org.elasticsearch.cluster.metadata.MetadataIndexTemplateService.resolveSettings;
import static org.elasticsearch.indices.cluster.IndicesClusterStateService.AllocatedIndices.IndexRemovalReason.NO_LONGER_ASSIGNED;

public class TransportSimulateIndexTemplateAction
    extends TransportMasterNodeReadAction<SimulateIndexTemplateRequest, SimulateIndexTemplateResponse> {

    private final MetadataIndexTemplateService indexTemplateService;
    private final NamedXContentRegistry xContentRegistry;
    private final IndicesService indicesService;
    private AliasValidator aliasValidator;

    @Inject
    public TransportSimulateIndexTemplateAction(TransportService transportService, ClusterService clusterService,
                                                ThreadPool threadPool, MetadataIndexTemplateService indexTemplateService,
                                                ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                                NamedXContentRegistry xContentRegistry, IndicesService indicesService) {
        super(SimulateIndexTemplateAction.NAME, transportService, clusterService, threadPool, actionFilters,
            SimulateIndexTemplateRequest::new, indexNameExpressionResolver);
        this.indexTemplateService = indexTemplateService;
        this.xContentRegistry = xContentRegistry;
        this.indicesService = indicesService;
        this.aliasValidator = new AliasValidator();
    }

    @Override
    protected String executor() {
        return ThreadPool.Names.SAME;
    }

    @Override
    protected SimulateIndexTemplateResponse read(StreamInput in) throws IOException {
        return new SimulateIndexTemplateResponse(in);
    }

    @Override
    protected void masterOperation(SimulateIndexTemplateRequest request, ClusterState state,
                                   ActionListener<SimulateIndexTemplateResponse> listener) throws Exception {
        ClusterState simulateOnClusterState = state;
        if (request.getIndexTemplateRequest() != null) {
            // we'll "locally" add the template defined by the user in the cluster state (as if it existed in the system)
            String simulateTemplateToAdd = "simulate_new_template_" + UUIDs.randomBase64UUID();
            simulateOnClusterState = indexTemplateService.addIndexTemplateV2(state, request.getIndexTemplateRequest().create(),
                simulateTemplateToAdd, request.getIndexTemplateRequest().indexTemplate());
        }

        String matchingTemplate = findV2Template(simulateOnClusterState.metadata(), request.getIndexName(), false);
        if (matchingTemplate == null) {
            listener.onResponse(new SimulateIndexTemplateResponse(null, null));
            return;
        }
        Settings settings = resolveSettings(simulateOnClusterState.metadata(), matchingTemplate);

        // empty request mapping as the user can't specify any explicit mappings via the simulate api
        Map<String, Map<String, Object>> mappings = resolveV2Mappings("{}", simulateOnClusterState, matchingTemplate, xContentRegistry);
        String mappingsJson = Strings.toString(XContentFactory.jsonBuilder()
            .startObject()
            .field(MapperService.SINGLE_MAPPING_NAME, mappings)
            .endObject());

        List<Map<String, AliasMetadata>> resolvedAliases = MetadataIndexTemplateService.resolveAliases(simulateOnClusterState.metadata(),
            matchingTemplate);

        // create the index with dummy settings in the cluster state so we can parse and validate the aliases
        Settings dummySettings = Settings.builder()
            .put(IndexMetadata.SETTING_VERSION_CREATED, Version.CURRENT)
            .put(settings)
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, 1)
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexMetadata.SETTING_INDEX_UUID, UUIDs.randomBase64UUID())
            .build();
        final IndexMetadata indexMetadata = IndexMetadata.builder(request.getIndexName()).settings(dummySettings).build();

        simulateOnClusterState = ClusterState.builder(simulateOnClusterState)
            .metadata(Metadata.builder(simulateOnClusterState.metadata())
                .put(indexMetadata, true)
                .build())
            .build();

        IndexService tempIndexService = indicesService.createIndex(indexMetadata, Collections.emptyList(), false);
        final Index index = tempIndexService.index();
        try (Closeable dummy = () -> tempIndexService.close("temp", false)) {
            List<AliasMetadata> aliases = MetadataCreateIndexService.resolveAndValidateAliases(request.getIndexName(),
                org.elasticsearch.common.collect.Set.of(), resolvedAliases, simulateOnClusterState.metadata(), aliasValidator,
                xContentRegistry,
                // the context is only used for validation so it's fine to pass fake values for the
                // shard id and the current timestamp
                tempIndexService.newQueryShardContext(0, null, () -> 0L, null));

            IndexTemplateV2 templateV2 = simulateOnClusterState.metadata().templatesV2().get(matchingTemplate);
            assert templateV2 != null : "the matched template must exist";

            Map<String, List<String>> overlapping = new HashMap<>();
            overlapping.putAll(findConflictingV1Templates(simulateOnClusterState, matchingTemplate, templateV2.indexPatterns()));
            overlapping.putAll(findConflictingV2Templates(simulateOnClusterState, matchingTemplate, templateV2.indexPatterns()));

            Template template = new Template(settings, mappingsJson == null ? null : new CompressedXContent(mappingsJson),
                aliases.stream().collect(Collectors.toMap(AliasMetadata::getAlias, Function.identity())));
            listener.onResponse(new SimulateIndexTemplateResponse(template, overlapping));
        } finally {
            if (index != null) {
                indicesService.removeIndex(index, NO_LONGER_ASSIGNED,
                    "created as part of a simulation for an index name matching the index templates in the system");
            }
        }
    }

    @Override
    protected ClusterBlockException checkBlock(SimulateIndexTemplateRequest request, ClusterState state) {
        return state.blocks().globalBlockedException(ClusterBlockLevel.METADATA_READ);
    }
}
