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

package org.elasticsearch.ingest;

import org.elasticsearch.action.admin.indices.mapping.put.PutMappingResponse;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.plugin.ingest.IngestPlugin;
import org.elasticsearch.plugin.ingest.transport.delete.DeletePipelineAction;
import org.elasticsearch.plugin.ingest.transport.delete.DeletePipelineRequestBuilder;
import org.elasticsearch.plugin.ingest.transport.delete.DeletePipelineResponse;
import org.elasticsearch.plugin.ingest.transport.get.GetPipelineAction;
import org.elasticsearch.plugin.ingest.transport.get.GetPipelineRequestBuilder;
import org.elasticsearch.plugin.ingest.transport.get.GetPipelineResponse;
import org.elasticsearch.plugin.ingest.transport.put.PutPipelineAction;
import org.elasticsearch.plugin.ingest.transport.put.PutPipelineRequestBuilder;
import org.elasticsearch.plugin.ingest.transport.simulate.SimulatePipelineAction;
import org.elasticsearch.plugin.ingest.transport.simulate.SimulatePipelineRequestBuilder;
import org.elasticsearch.plugin.ingest.transport.simulate.SimulatePipelineResponse;
import org.elasticsearch.plugin.ingest.transport.simulate.SimulatedItemResponse;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.*;

import static org.elasticsearch.common.xcontent.XContentFactory.jsonBuilder;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.notNullValue;

public class IngestClientIT extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return pluginList(IngestPlugin.class);
    }

    @Override
    protected Collection<Class<? extends Plugin>> transportClientPlugins() {
        return nodePlugins();

    }

    public void testSimulate() throws Exception {
        new PutPipelineRequestBuilder(client(), PutPipelineAction.INSTANCE)
                .setId("_id")
                .setSource(jsonBuilder().startObject()
                        .field("description", "my_pipeline")
                        .startArray("processors")
                        .startObject()
                        .startObject("grok")
                        .field("field", "field1")
                        .field("pattern", "%{NUMBER:val:float} %{NUMBER:status:int} <%{WORD:msg}>")
                        .endObject()
                        .endObject()
                        .endArray()
                        .endObject().bytes())
                .get();
        assertBusy(new Runnable() {
            @Override
            public void run() {
                GetPipelineResponse response = new GetPipelineRequestBuilder(client(), GetPipelineAction.INSTANCE)
                        .setIds("_id")
                        .get();
                assertThat(response.isFound(), is(true));
                assertThat(response.pipelines().get("_id"), notNullValue());
            }
        });

        SimulatePipelineResponse response = new SimulatePipelineRequestBuilder(client(), SimulatePipelineAction.INSTANCE)
                .setId("_id")
                .setSource(jsonBuilder().startObject()
                        .startArray("docs")
                        .startObject()
                        .field("_index", "index")
                        .field("_type", "type")
                        .field("_id", "id")
                        .startObject("_source")
                        .field("foo", "bar")
                        .endObject()
                        .endObject()
                        .endArray()
                        .endObject().bytes())
                .get();

        Map<String, Object> expectedDoc = new HashMap<>();
        expectedDoc.put("foo", "bar");
        Data expectedData = new Data("index", "type", "id", expectedDoc);
        SimulatedItemResponse expectedResponse = new SimulatedItemResponse(expectedData);
        SimulatedItemResponse[] expectedResponses = new SimulatedItemResponse[] { expectedResponse };

        assertThat(response.responses().length, equalTo(1));
        assertThat(response.responses()[0].getData().getIndex(), equalTo(expectedResponse.getData().getIndex()));
        assertThat(response.responses()[0].getData(), equalTo(expectedResponse.getData()));
        assertThat(response.responses()[0], equalTo(expectedResponse));
        assertThat(response.responses(), equalTo(expectedResponses));
        assertThat(response.pipelineId(), equalTo("_id"));
    }

    public void test() throws Exception {
        new PutPipelineRequestBuilder(client(), PutPipelineAction.INSTANCE)
                .setId("_id")
                .setSource(jsonBuilder().startObject()
                        .field("description", "my_pipeline")
                        .startArray("processors")
                        .startObject()
                        .startObject("grok")
                        .field("field", "field1")
                        .field("pattern", "%{NUMBER:val:float} %{NUMBER:status:int} <%{WORD:msg}>")
                        .endObject()
                        .endObject()
                        .endArray()
                        .endObject().bytes())
                .get();
        assertBusy(new Runnable() {
            @Override
            public void run() {
                GetPipelineResponse response = new GetPipelineRequestBuilder(client(), GetPipelineAction.INSTANCE)
                        .setIds("_id")
                        .get();
                assertThat(response.isFound(), is(true));
                assertThat(response.pipelines().get("_id"), notNullValue());
            }
        });

        createIndex("test");
        XContentBuilder updateMappingBuilder = jsonBuilder().startObject().startObject("properties")
                .startObject("status").field("type", "integer").endObject()
                .startObject("val").field("type", "float").endObject()
                .endObject();
        PutMappingResponse putMappingResponse = client().admin().indices()
                .preparePutMapping("test").setType("type").setSource(updateMappingBuilder).get();
        assertAcked(putMappingResponse);

        client().prepareIndex("test", "type", "1").setSource("field1", "123.42 400 <foo>")
                .putHeader(IngestPlugin.PIPELINE_ID_PARAM, "_id")
                .get();

        assertBusy(new Runnable() {
            @Override
            public void run() {
                Map<String, Object> doc = client().prepareGet("test", "type", "1")
                        .get().getSourceAsMap();
                assertThat(doc.get("val"), equalTo(123.42));
                assertThat(doc.get("status"), equalTo(400));
                assertThat(doc.get("msg"), equalTo("foo"));
            }
        });

        client().prepareBulk().add(
                client().prepareIndex("test", "type", "2").setSource("field1", "123.42 400 <foo>")
        ).putHeader(IngestPlugin.PIPELINE_ID_PARAM, "_id").get();
        assertBusy(new Runnable() {
            @Override
            public void run() {
                Map<String, Object> doc = client().prepareGet("test", "type", "2").get().getSourceAsMap();
                assertThat(doc.get("val"), equalTo(123.42));
                assertThat(doc.get("status"), equalTo(400));
                assertThat(doc.get("msg"), equalTo("foo"));
            }
        });

        DeletePipelineResponse response = new DeletePipelineRequestBuilder(client(), DeletePipelineAction.INSTANCE)
                .setId("_id")
                .get();
        assertThat(response.found(), is(true));
        assertThat(response.id(), equalTo("_id"));

        assertBusy(new Runnable() {
            @Override
            public void run() {
                GetPipelineResponse response = new GetPipelineRequestBuilder(client(), GetPipelineAction.INSTANCE)
                        .setIds("_id")
                        .get();
                assertThat(response.isFound(), is(false));
                assertThat(response.pipelines().get("_id"), nullValue());
            }
        });
    }

    @Override
    protected boolean enableMockModules() {
        return false;
    }
}
