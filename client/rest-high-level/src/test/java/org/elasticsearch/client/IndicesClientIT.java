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

package org.elasticsearch.client;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.rest.RestStatus;

import java.io.IOException;
import java.util.Map;

import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_NUMBER_OF_REPLICAS;

public class IndicesClientIT extends ESRestHighLevelClientTestCase {

    @SuppressWarnings("unchecked")
    public void testCreateIndex() throws IOException {
        {
            // Create index
            String indexName = "plain_index";
            assertFalse(indexExists(indexName));

            CreateIndexRequest createIndexRequest = new CreateIndexRequest(indexName);

            CreateIndexResponse createIndexResponse =
                execute(createIndexRequest, highLevelClient().indices()::createIndex, highLevelClient().indices()::createIndexAsync);
            assertTrue(createIndexResponse.isAcknowledged());

            assertTrue(indexExists(indexName));
        }
        {
            // Create index with mappings, aliases and settings
            String indexName = "rich_index";
            assertFalse(indexExists(indexName));

            CreateIndexRequest createIndexRequest = new CreateIndexRequest(indexName);

            Alias alias = new Alias("alias_name");
            alias.filter("{\"term\":{\"year\":2016}}");
            alias.routing("1");
            createIndexRequest.alias(alias);

            Settings.Builder settings = Settings.builder();
            settings.put(SETTING_NUMBER_OF_REPLICAS, 2);
            createIndexRequest.settings(settings);

            XContentBuilder mappingBuilder = JsonXContent.contentBuilder();
            mappingBuilder.startObject().startObject("properties").startObject("field");
            mappingBuilder.field("type", "text");
            mappingBuilder.endObject().endObject().endObject();
            createIndexRequest.mapping("type_name", mappingBuilder);

            CreateIndexResponse createIndexResponse =
                execute(createIndexRequest, highLevelClient().indices()::createIndex, highLevelClient().indices()::createIndexAsync);
            assertTrue(createIndexResponse.isAcknowledged());

            Map<String, Object> indexMetaData = getIndexMetadata(indexName);

            Map<String, Object> settingsData = (Map) indexMetaData.get("settings");
            Map<String, Object> indexSettings = (Map) settingsData.get("index");
            assertEquals("2", indexSettings.get("number_of_replicas"));

            Map<String, Object> aliasesData = (Map) indexMetaData.get("aliases");
            Map<String, Object> aliasData = (Map) aliasesData.get("alias_name");
            assertEquals("1", aliasData.get("index_routing"));
            Map<String, Object> filter = (Map) aliasData.get("filter");
            Map<String, Object> term = (Map) filter.get("term");
            assertEquals(2016, term.get("year"));

            Map<String, Object> mappingsData = (Map) indexMetaData.get("mappings");
            Map<String, Object> typeData = (Map) mappingsData.get("type_name");
            Map<String, Object> properties = (Map) typeData.get("properties");
            Map<String, Object> field = (Map) properties.get("field");

            assertEquals("text", field.get("type"));
        }
    }

    public void testDeleteIndex() throws IOException {
        {
            // Delete index if exists
            String indexName = "test_index";
            createIndex(indexName);

            DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(indexName);
            DeleteIndexResponse deleteIndexResponse =
                execute(deleteIndexRequest, highLevelClient().indices()::deleteIndex, highLevelClient().indices()::deleteIndexAsync);
            assertTrue(deleteIndexResponse.isAcknowledged());

            assertFalse(indexExists(indexName));
        }
        {
            // Return 404 if index doesn't exist
            String nonExistentIndex = "non_existent_index";
            assertFalse(indexExists(nonExistentIndex));

            DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(nonExistentIndex);

            ElasticsearchException exception = expectThrows(ElasticsearchException.class,
                () -> execute(deleteIndexRequest, highLevelClient().indices()::deleteIndex, highLevelClient().indices()::deleteIndexAsync));
            assertEquals(RestStatus.NOT_FOUND, exception.status());
        }
    }

    private static void createIndex(String index) throws IOException {
        Response response = client().performRequest("PUT", index);

        assertEquals(200, response.getStatusLine().getStatusCode());
    }

    private static boolean indexExists(String index) throws IOException {
        Response response = client().performRequest("HEAD", index);

        return response.getStatusLine().getStatusCode() == 200;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getIndexMetadata(String index) throws IOException {
        Response response = client().performRequest("GET", index);

        XContentType entityContentType = XContentType.fromMediaTypeOrFormat(response.getEntity().getContentType().getValue());
        Map<String, Object> responseEntity = XContentHelper.convertToMap(entityContentType.xContent(), response.getEntity().getContent(),
            false);

        Map<String, Object> indexMetaData = (Map) responseEntity.get(index);
        assertNotNull(indexMetaData);

        return indexMetaData;
    }
}
