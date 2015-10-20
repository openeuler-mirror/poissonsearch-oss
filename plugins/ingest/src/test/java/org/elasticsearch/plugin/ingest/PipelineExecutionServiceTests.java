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

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.ingest.Data;
import org.elasticsearch.ingest.Pipeline;
import org.elasticsearch.ingest.processor.Processor;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.threadpool.ThreadPool;
import org.junit.After;
import org.junit.Before;

import java.util.Collections;
import java.util.Map;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

public class PipelineExecutionServiceTests extends ESTestCase {

    private PipelineStore store;
    private ThreadPool threadPool;
    private PipelineExecutionService executionService;

    @Before
    public void setup() {
        store = mock(PipelineStore.class);
        threadPool = new ThreadPool(
                Settings.builder()
                        .put("name", "_name")
                        .put(PipelineExecutionService.additionalSettings(Settings.EMPTY))
                        .build()
        );
        executionService = new PipelineExecutionService(store, threadPool);
    }

    @After
    public void destroy() {
        threadPool.shutdown();
    }

    public void testExecute_pipelineDoesNotExist() {
        when(store.get("_id")).thenReturn(null);
        Data data = new Data("_index", "_type", "_id", Collections.emptyMap());
        PipelineExecutionService.Listener listener = mock(PipelineExecutionService.Listener.class);
        executionService.execute(data, "_id", listener);
        verify(listener).failed(any(IllegalArgumentException.class));
        verify(listener, times(0)).executed(data);
    }

    public void testExecute_success() throws Exception {
        Pipeline.Builder builder = new Pipeline.Builder("_id");
        Processor processor = mock(Processor.class);
        builder.addProcessors(new Processor.Builder() {
            @Override
            public void fromMap(Map<String, Object> config) {
            }

            @Override
            public Processor build() {
                return processor;
            }
        });

        when(store.get("_id")).thenReturn(builder.build());

        Data data = new Data("_index", "_type", "_id", Collections.emptyMap());
        PipelineExecutionService.Listener listener = mock(PipelineExecutionService.Listener.class);
        executionService.execute(data, "_id", listener);
        assertBusy(new Runnable() {
            @Override
            public void run() {
                verify(processor).execute(data);
                verify(listener).executed(data);
                verify(listener, times(0)).failed(any(Exception.class));
            }
        });
    }

    public void testExecute_failure() throws Exception {
        Pipeline.Builder builder = new Pipeline.Builder("_id");
        Processor processor = mock(Processor.class);
        builder.addProcessors(new Processor.Builder() {
            @Override
            public void fromMap(Map<String, Object> config) {
            }

            @Override
            public Processor build() {
                return processor;
            }
        });

        when(store.get("_id")).thenReturn(builder.build());
        Data data = new Data("_index", "_type", "_id", Collections.emptyMap());
        doThrow(new RuntimeException()).when(processor).execute(data);
        PipelineExecutionService.Listener listener = mock(PipelineExecutionService.Listener.class);
        executionService.execute(data, "_id", listener);
        assertBusy(new Runnable() {
            @Override
            public void run() {
                verify(processor).execute(data);
                verify(listener, times(0)).executed(data);
                verify(listener).failed(any(RuntimeException.class));
            }
        });
    }

}
