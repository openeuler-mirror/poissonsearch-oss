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

package org.elasticsearch.client.documentation;

import org.apache.http.entity.ContentType;
import org.apache.http.nio.entity.NStringEntity;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.DocWriteRequest;
import org.elasticsearch.action.DocWriteResponse;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.get.GetRequest;
import org.elasticsearch.action.get.GetResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.ActiveShardCount;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.action.support.replication.ReplicationResponse;
import org.elasticsearch.action.update.UpdateRequest;
import org.elasticsearch.action.update.UpdateResponse;
import org.elasticsearch.client.ESRestHighLevelClientTestCase;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.VersionType;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.fetch.subphase.FetchSourceContext;

import java.io.IOException;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * This class is used to generate the Java CRUD API documentation.
 * You need to wrap your code between two tags like:
 * // tag::example[]
 * // end::example[]
 *
 * Where example is your tag name.
 *
 * Then in the documentation, you can extract what is between tag and end tags with
 * ["source","java",subs="attributes,callouts,macros"]
 * --------------------------------------------------
 * include-tagged::{doc-tests}/CRUDDocumentationIT.java[example]
 * --------------------------------------------------
 */
public class CRUDDocumentationIT extends ESRestHighLevelClientTestCase {
    
    public void testIndex() throws IOException {
        RestHighLevelClient client = highLevelClient();

        {
            //tag::index-request-map
            Map<String, Object> jsonMap = new HashMap<>();
            jsonMap.put("user", "kimchy");
            jsonMap.put("postDate",new Date());
            jsonMap.put("message","trying out Elasticsearch");
            IndexRequest indexRequest = new IndexRequest("posts", "doc", "1")
                    .source(jsonMap); // <1>
            //end::index-request-map
            IndexResponse indexResponse = client.index(indexRequest);
            assertEquals(indexResponse.getResult(), DocWriteResponse.Result.CREATED);
        }
        {
            //tag::index-request-xcontent
            XContentBuilder builder = XContentFactory.jsonBuilder();
            builder.startObject();
            {
                builder.field("user", "kimchy");
                builder.field("postDate", new Date());
                builder.field("message", "trying out Elasticsearch");
            }
            builder.endObject();
            IndexRequest indexRequest = new IndexRequest("posts", "doc", "1")
                    .source(builder);  // <1>
            //end::index-request-xcontent
            IndexResponse indexResponse = client.index(indexRequest);
            assertEquals(indexResponse.getResult(), DocWriteResponse.Result.UPDATED);
        }
        {
            //tag::index-request-shortcut
            IndexRequest indexRequest = new IndexRequest("posts", "doc", "1")
                    .source("user", "kimchy",
                            "postDate", new Date(),
                            "message", "trying out Elasticsearch"); // <1>
            //end::index-request-shortcut
            IndexResponse indexResponse = client.index(indexRequest);
            assertEquals(indexResponse.getResult(), DocWriteResponse.Result.UPDATED);
        }
        {
            //tag::index-request-string
            IndexRequest request = new IndexRequest(
                    "posts", // <1>
                    "doc",  // <2>
                    "1");   // <3>
            String jsonString = "{" +
                    "\"user\":\"kimchy\"," +
                    "\"postDate\":\"2013-01-30\"," +
                    "\"message\":\"trying out Elasticsearch\"" +
                    "}";
            request.source(jsonString, XContentType.JSON); // <4>
            //end::index-request-string

            // tag::index-execute
            IndexResponse indexResponse = client.index(request);
            // end::index-execute
            assertEquals(indexResponse.getResult(), DocWriteResponse.Result.UPDATED);

            // tag::index-response
            String index = indexResponse.getIndex();
            String type = indexResponse.getType();
            String id = indexResponse.getId();
            long version = indexResponse.getVersion();
            if (indexResponse.getResult() == DocWriteResponse.Result.CREATED) {
                // <1>
            } else if (indexResponse.getResult() == DocWriteResponse.Result.UPDATED) {
                // <2>
            }
            ReplicationResponse.ShardInfo shardInfo = indexResponse.getShardInfo();
            if (shardInfo.getTotal() != shardInfo.getSuccessful()) {
                // <3>
            }
            if (shardInfo.getFailed() > 0) {
                for (ReplicationResponse.ShardInfo.Failure failure : shardInfo.getFailures()) {
                    String reason = failure.reason(); // <4>
                }
            }
            // end::index-response

            // tag::index-execute-async
            client.indexAsync(request, new ActionListener<IndexResponse>() {
                @Override
                public void onResponse(IndexResponse indexResponse) {
                    // <1>
                }

                @Override
                public void onFailure(Exception e) {
                    // <2>
                }
            });
            // end::index-execute-async
        }
        {
            IndexRequest request = new IndexRequest("posts", "doc", "1");
            // tag::index-request-routing
            request.routing("routing"); // <1>
            // end::index-request-routing
            // tag::index-request-parent
            request.parent("parent"); // <1>
            // end::index-request-parent
            // tag::index-request-timeout
            request.timeout(TimeValue.timeValueSeconds(1)); // <1>
            request.timeout("1s"); // <2>
            // end::index-request-timeout
            // tag::index-request-refresh
            request.setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL); // <1>
            request.setRefreshPolicy("wait_for");                            // <2>
            // end::index-request-refresh
            // tag::index-request-version
            request.version(2); // <1>
            // end::index-request-version
            // tag::index-request-version-type
            request.versionType(VersionType.EXTERNAL); // <1>
            // end::index-request-version-type
            // tag::index-request-op-type
            request.opType(DocWriteRequest.OpType.CREATE); // <1>
            request.opType("create"); // <2>
            // end::index-request-op-type
            // tag::index-request-pipeline
            request.setPipeline("pipeline"); // <1>
            // end::index-request-pipeline
        }
        {
            // tag::index-conflict
            IndexRequest request = new IndexRequest("posts", "doc", "1")
                    .source("field", "value")
                    .version(1);
            try {
                IndexResponse response = client.index(request);
            } catch(ElasticsearchException e) {
                if (e.status() == RestStatus.CONFLICT) {
                    // <1>
                }
            }
            // end::index-conflict

        }
        {
            // tag::index-optype
            IndexRequest request = new IndexRequest("posts", "doc", "1")
                    .source("field", "value")
                    .opType(DocWriteRequest.OpType.CREATE);
            try {
                IndexResponse response = client.index(request);
            } catch(ElasticsearchException e) {
                if (e.status() == RestStatus.CONFLICT) {
                    // <1>
                }
            }
            // end::index-optype
        }
    }

    public void testDelete() throws IOException {
        RestHighLevelClient client = highLevelClient();

        {
            IndexRequest indexRequest = new IndexRequest("posts", "doc", "1").source("field", "value");
            IndexResponse indexResponse = client.index(indexRequest);
            assertSame(indexResponse.status(), RestStatus.CREATED);
        }

        {
            // tag::delete-request
            DeleteRequest request = new DeleteRequest(
                    "posts",    // <1>
                    "doc",     // <2>
                    "1");      // <3>
            // end::delete-request

            // tag::delete-execute
            DeleteResponse deleteResponse = client.delete(request);
            // end::delete-execute
            assertSame(deleteResponse.getResult(), DocWriteResponse.Result.DELETED);

            // tag::delete-response
            String index = deleteResponse.getIndex();
            String type = deleteResponse.getType();
            String id = deleteResponse.getId();
            long version = deleteResponse.getVersion();
            ReplicationResponse.ShardInfo shardInfo = deleteResponse.getShardInfo();
            if (shardInfo.getTotal() != shardInfo.getSuccessful()) {
                // <1>
            }
            if (shardInfo.getFailed() > 0) {
                for (ReplicationResponse.ShardInfo.Failure failure : shardInfo.getFailures()) {
                    String reason = failure.reason(); // <2>
                }
            }
            // end::delete-response

            // tag::delete-execute-async
            client.deleteAsync(request, new ActionListener<DeleteResponse>() {
                @Override
                public void onResponse(DeleteResponse deleteResponse) {
                    // <1>
                }

                @Override
                public void onFailure(Exception e) {
                    // <2>
                }
            });
            // end::delete-execute-async
        }

        {
            DeleteRequest request = new DeleteRequest("posts", "doc", "1");
            // tag::delete-request-routing
            request.routing("routing"); // <1>
            // end::delete-request-routing
            // tag::delete-request-parent
            request.parent("parent"); // <1>
            // end::delete-request-parent
            // tag::delete-request-timeout
            request.timeout(TimeValue.timeValueMinutes(2)); // <1>
            request.timeout("2m"); // <2>
            // end::delete-request-timeout
            // tag::delete-request-refresh
            request.setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL); // <1>
            request.setRefreshPolicy("wait_for");                            // <2>
            // end::delete-request-refresh
            // tag::delete-request-version
            request.version(2); // <1>
            // end::delete-request-version
            // tag::delete-request-version-type
            request.versionType(VersionType.EXTERNAL); // <1>
            // end::delete-request-version-type
        }

        {
            // tag::delete-notfound
            DeleteRequest request = new DeleteRequest("posts", "doc", "does_not_exist");
            DeleteResponse deleteResponse = client.delete(request);
            if (deleteResponse.getResult() == DocWriteResponse.Result.NOT_FOUND) {
                // <1>
            }
            // end::delete-notfound
        }

        {
            IndexResponse indexResponse = client.index(new IndexRequest("posts", "doc", "1").source("field", "value"));
            assertSame(indexResponse.status(), RestStatus.CREATED);

            // tag::delete-conflict
            try {
                DeleteRequest request = new DeleteRequest("posts", "doc", "1").version(2);
                DeleteResponse deleteResponse = client.delete(request);
            } catch (ElasticsearchException exception) {
                if (exception.status() == RestStatus.CONFLICT) {
                    // <1>
                }
            }
            // end::delete-conflict
        }
    }

    public void testBulk() throws IOException {
        RestHighLevelClient client = highLevelClient();
        {
            // tag::bulk-request
            BulkRequest request = new BulkRequest(); // <1>
            request.add(new IndexRequest("posts", "doc", "1")  // <2>
                    .source(XContentType.JSON,"field", "foo"));
            request.add(new IndexRequest("posts", "doc", "2")  // <3>
                    .source(XContentType.JSON,"field", "bar"));
            request.add(new IndexRequest("posts", "doc", "3")  // <4>
                    .source(XContentType.JSON,"field", "baz"));
            // end::bulk-request
            // tag::bulk-execute
            BulkResponse bulkResponse = client.bulk(request);
            // end::bulk-execute
            assertSame(bulkResponse.status(), RestStatus.OK);
            assertFalse(bulkResponse.hasFailures());
        }
        {
            // tag::bulk-request-with-mixed-operations
            BulkRequest request = new BulkRequest();
            request.add(new DeleteRequest("posts", "doc", "3")); // <1>
            request.add(new UpdateRequest("posts", "doc", "2") // <2>
                    .doc(XContentType.JSON,"other", "test"));
            request.add(new IndexRequest("posts", "doc", "4")  // <3>
                    .source(XContentType.JSON,"field", "baz"));
            // end::bulk-request-with-mixed-operations
            BulkResponse bulkResponse = client.bulk(request);
            assertSame(bulkResponse.status(), RestStatus.OK);
            assertFalse(bulkResponse.hasFailures());

            // tag::bulk-response
            for (BulkItemResponse bulkItemResponse : bulkResponse) { // <1>
                DocWriteResponse itemResponse = bulkItemResponse.getResponse(); // <2>

                if (bulkItemResponse.getOpType() == DocWriteRequest.OpType.INDEX
                        || bulkItemResponse.getOpType() == DocWriteRequest.OpType.CREATE) { // <3>
                    IndexResponse indexResponse = (IndexResponse) itemResponse;

                } else if (bulkItemResponse.getOpType() == DocWriteRequest.OpType.UPDATE) { // <4>
                    UpdateResponse updateResponse = (UpdateResponse) itemResponse;

                } else if (bulkItemResponse.getOpType() == DocWriteRequest.OpType.DELETE) { // <5>
                    DeleteResponse deleteResponse = (DeleteResponse) itemResponse;
                }
            }
            // end::bulk-response
            // tag::bulk-has-failures
            if (bulkResponse.hasFailures()) { // <1>

            }
            // end::bulk-has-failures
            // tag::bulk-errors
            for (BulkItemResponse bulkItemResponse : bulkResponse) {
                if (bulkItemResponse.isFailed()) { // <1>
                    BulkItemResponse.Failure failure = bulkItemResponse.getFailure(); // <2>

                }
            }
            // end::bulk-errors
        }
        {
            BulkRequest request = new BulkRequest();
            // tag::bulk-request-timeout
            request.timeout(TimeValue.timeValueMinutes(2)); // <1>
            request.timeout("2m"); // <2>
            // end::bulk-request-timeout
            // tag::bulk-request-refresh
            request.setRefreshPolicy(WriteRequest.RefreshPolicy.WAIT_UNTIL); // <1>
            request.setRefreshPolicy("wait_for");                            // <2>
            // end::bulk-request-refresh
            // tag::bulk-request-active-shards
            request.waitForActiveShards(2); // <1>
            request.waitForActiveShards(ActiveShardCount.ALL); // <2>
            // end::bulk-request-active-shards

            // tag::bulk-execute-async
            client.bulkAsync(request, new ActionListener<BulkResponse>() {
                @Override
                public void onResponse(BulkResponse bulkResponse) {
                    // <1>
                }

                @Override
                public void onFailure(Exception e) {
                    // <2>
                }
            });
            // end::bulk-execute-async
        }
    }

    public void testGet() throws IOException {
        RestHighLevelClient client = highLevelClient();
        {
            String mappings = "{\n" +
                    "    \"mappings\" : {\n" +
                    "        \"doc\" : {\n" +
                    "            \"properties\" : {\n" +
                    "                \"message\" : {\n" +
                    "                    \"type\": \"text\",\n" +
                    "                    \"store\": true\n" +
                    "                }\n" +
                    "            }\n" +
                    "        }\n" +
                    "    }\n" +
                    "}";

            NStringEntity entity = new NStringEntity(mappings, ContentType.APPLICATION_JSON);
            Response response = client().performRequest("PUT", "/posts", Collections.emptyMap(), entity);
            assertEquals(200, response.getStatusLine().getStatusCode());

            IndexRequest indexRequest = new IndexRequest("posts", "doc", "1")
                    .source("user", "kimchy",
                            "postDate", new Date(),
                            "message", "trying out Elasticsearch");
            IndexResponse indexResponse = client.index(indexRequest);
            assertEquals(indexResponse.getResult(), DocWriteResponse.Result.CREATED);
        }
        {
            //tag::get-request
            GetRequest getRequest = new GetRequest(
                    "posts", // <1>
                    "doc",  // <2>
                    "1");   // <3>
            //end::get-request

            //tag::get-execute
            GetResponse getResponse = client.get(getRequest);
            //end::get-execute
            assertTrue(getResponse.isExists());
            assertEquals(3, getResponse.getSourceAsMap().size());
            //tag::get-response
            String index = getResponse.getIndex();
            String type = getResponse.getType();
            String id = getResponse.getId();
            if (getResponse.isExists()) {
                long version = getResponse.getVersion();
                String sourceAsString = getResponse.getSourceAsString();        // <1>
                Map<String, Object> sourceAsMap = getResponse.getSourceAsMap(); // <2>
                byte[] sourceAsBytes = getResponse.getSourceAsBytes();          // <3>
            } else {
                // <4>
            }
            //end::get-response
        }
        {
            GetRequest request = new GetRequest("posts", "doc", "1");
            //tag::get-request-no-source
            request.fetchSourceContext(new FetchSourceContext(false)); // <1>
            //end::get-request-no-source
            GetResponse getResponse = client.get(request);
            assertNull(getResponse.getSourceInternal());
        }
        {
            GetRequest request = new GetRequest("posts", "doc", "1");
            //tag::get-request-source-include
            String[] includes = new String[]{"message", "*Date"};
            String[] excludes = Strings.EMPTY_ARRAY;
            FetchSourceContext fetchSourceContext = new FetchSourceContext(true, includes, excludes);
            request.fetchSourceContext(fetchSourceContext); // <1>
            //end::get-request-source-include
            GetResponse getResponse = client.get(request);
            Map<String, Object> sourceAsMap = getResponse.getSourceAsMap();
            assertEquals(2, sourceAsMap.size());
            assertEquals("trying out Elasticsearch", sourceAsMap.get("message"));
            assertTrue(sourceAsMap.containsKey("postDate"));
        }
        {
            GetRequest request = new GetRequest("posts", "doc", "1");
            //tag::get-request-source-exclude
            String[] includes = Strings.EMPTY_ARRAY;
            String[] excludes = new String[]{"message"};
            FetchSourceContext fetchSourceContext = new FetchSourceContext(true, includes, excludes);
            request.fetchSourceContext(fetchSourceContext); // <1>
            //end::get-request-source-exclude
            GetResponse getResponse = client.get(request);
            Map<String, Object> sourceAsMap = getResponse.getSourceAsMap();
            assertEquals(2, sourceAsMap.size());
            assertEquals("kimchy", sourceAsMap.get("user"));
            assertTrue(sourceAsMap.containsKey("postDate"));
        }
        {
            GetRequest request = new GetRequest("posts", "doc", "1");
            //tag::get-request-stored
            request.storedFields("message"); // <1>
            GetResponse getResponse = client.get(request);
            String message = getResponse.getField("message").getValue(); // <2>
            //end::get-request-stored
            assertEquals("trying out Elasticsearch", message);
            assertEquals(1, getResponse.getFields().size());
            assertNull(getResponse.getSourceInternal());
        }
        {
            GetRequest request = new GetRequest("posts", "doc", "1");
            //tag::get-request-routing
            request.routing("routing"); // <1>
            //end::get-request-routing
            //tag::get-request-parent
            request.parent("parent"); // <1>
            //end::get-request-parent
            //tag::get-request-preference
            request.preference("preference"); // <1>
            //end::get-request-preference
            //tag::get-request-realtime
            request.realtime(false); // <1>
            //end::get-request-realtime
            //tag::get-request-refresh
            request.refresh(true); // <1>
            //end::get-request-refresh
            //tag::get-request-version
            request.version(2); // <1>
            //end::get-request-version
            //tag::get-request-version-type
            request.versionType(VersionType.EXTERNAL); // <1>
            //end::get-request-version-type
        }
        {
            GetRequest request = new GetRequest("posts", "doc", "1");
            //tag::get-execute-async
            client.getAsync(request, new ActionListener<GetResponse>() {
                @Override
                public void onResponse(GetResponse getResponse) {
                    // <1>
                }

                @Override
                public void onFailure(Exception e) {
                    // <2>
                }
            });
            //end::get-execute-async
        }
        {
            //tag::get-indexnotfound
            GetRequest request = new GetRequest("does_not_exist", "doc", "1");
            try {
                GetResponse getResponse = client.get(request);
            } catch (ElasticsearchException e) {
                if (e.status() == RestStatus.NOT_FOUND) {
                    // <1>
                }
            }
            //end::get-indexnotfound
        }
        {
            // tag::get-conflict
            try {
                GetRequest request = new GetRequest("posts", "doc", "1").version(2);
                GetResponse getResponse = client.get(request);
            } catch (ElasticsearchException exception) {
                if (exception.status() == RestStatus.CONFLICT) {
                    // <1>
                }
            }
            // end::get-conflict
        }
    }
}
