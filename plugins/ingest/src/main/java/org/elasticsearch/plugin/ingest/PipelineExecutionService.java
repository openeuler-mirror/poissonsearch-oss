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

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.support.LoggerMessageFormat;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.ingest.Data;
import org.elasticsearch.ingest.Pipeline;
import org.elasticsearch.ingest.processor.Processor;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.Map;

public class PipelineExecutionService {

    static final String THREAD_POOL_NAME = IngestPlugin.NAME;

    private final PipelineStore store;
    private final ThreadPool threadPool;

    @Inject
    public PipelineExecutionService(PipelineStore store, ThreadPool threadPool) {
        this.store = store;
        this.threadPool = threadPool;
    }

    public Pipeline getPipeline(String pipelineId) {
        Pipeline pipeline = store.get(pipelineId);

        if (pipeline == null) {
            throw new IllegalArgumentException(LoggerMessageFormat.format("pipeline with id [{}] does not exist", pipelineId));
        }

        return pipeline;
    }

    public void execute(Data data, String pipelineId, Listener listener) {
        try {
            execute(data, getPipeline(pipelineId), listener);
        } catch (IllegalArgumentException e) {
            listener.failed(e);
        }
    }

    public void execute(Data data, Pipeline pipeline, Listener listener) {
        threadPool.executor(THREAD_POOL_NAME).execute(new Runnable() {
            @Override
            public void run() {
                try {
                    pipeline.execute(data);
                    listener.executed(data);
                } catch (Exception e) {
                    listener.failed(e);
                }
            }
        });
    }

    public interface Listener {

        void executed(Data data);

        void failed(Exception e);

    }

    public static Settings additionalSettings(Settings nodeSettings) {
        Settings settings = nodeSettings.getAsSettings("threadpool." + THREAD_POOL_NAME);
        if (!settings.names().isEmpty()) {
            // the TP is already configured in the node settings
            // no need for additional settings
            return Settings.EMPTY;
        }
        int availableProcessors = EsExecutors.boundedNumberOfProcessors(nodeSettings);
        return Settings.builder()
                .put("threadpool." + THREAD_POOL_NAME + ".type", "fixed")
                .put("threadpool." + THREAD_POOL_NAME + ".size", availableProcessors)
                .put("threadpool." + THREAD_POOL_NAME + ".queue_size", 200)
                .build();
    }

}
