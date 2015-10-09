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


package org.elasticsearch.plugin.ingest;

import org.elasticsearch.action.ActionModule;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.ingest.rest.RestDeletePipelineAction;
import org.elasticsearch.plugin.ingest.rest.RestGetPipelineAction;
import org.elasticsearch.plugin.ingest.rest.RestPutPipelineAction;
import org.elasticsearch.plugin.ingest.transport.IngestActionFilter;
import org.elasticsearch.plugin.ingest.transport.delete.DeletePipelineAction;
import org.elasticsearch.plugin.ingest.transport.delete.DeletePipelineTransportAction;
import org.elasticsearch.plugin.ingest.transport.get.GetPipelineAction;
import org.elasticsearch.plugin.ingest.transport.get.GetPipelineTransportAction;
import org.elasticsearch.plugin.ingest.transport.put.PutPipelineAction;
import org.elasticsearch.plugin.ingest.transport.put.PutPipelineTransportAction;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.rest.RestModule;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;

public class IngestPlugin extends Plugin {

    public static final String INGEST_PARAM_CONTEXT_KEY = "__ingest__";
    public static final String INGEST_PARAM = "ingest";
    public static final String INGEST_ALREADY_PROCESSED = "ingest_already_processed";
    public static final String NAME = "ingest";

    private final Settings nodeSettings;
    private final boolean transportClient;

    public IngestPlugin(Settings nodeSettings) {
        this.nodeSettings = nodeSettings;
        transportClient = "transport".equals(nodeSettings.get(Client.CLIENT_TYPE_SETTING));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Plugin that allows to configure pipelines to preprocess documents before indexing";
    }

    @Override
    public Collection<Module> nodeModules() {
        if (transportClient) {
            return Collections.emptyList();
        } else {
            return Collections.singletonList(new IngestModule());
        }
    }

    @Override
    public Collection<Class<? extends LifecycleComponent>> nodeServices() {
        if (transportClient) {
            return Collections.emptyList();
        } else {
            return Arrays.asList(PipelineStore.class, PipelineStoreClient.class);
        }
    }

    @Override
    public Settings additionalSettings() {
        return settingsBuilder()
                .put(PipelineExecutionService.additionalSettings(nodeSettings))
                .build();
    }

    public void onModule(ActionModule module) {
        if (!transportClient) {
            module.registerFilter(IngestActionFilter.class);
        }
        module.registerAction(PutPipelineAction.INSTANCE, PutPipelineTransportAction.class);
        module.registerAction(GetPipelineAction.INSTANCE, GetPipelineTransportAction.class);
        module.registerAction(DeletePipelineAction.INSTANCE, DeletePipelineTransportAction.class);
    }

    public void onModule(RestModule restModule) {
        restModule.addRestAction(RestPutPipelineAction.class);
        restModule.addRestAction(RestGetPipelineAction.class);
        restModule.addRestAction(RestDeletePipelineAction.class);
    }

}
