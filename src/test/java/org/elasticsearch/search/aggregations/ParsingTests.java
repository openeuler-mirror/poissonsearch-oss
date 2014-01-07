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

package org.elasticsearch.search.aggregations;

import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.junit.Test;

public class ParsingTests extends ElasticsearchIntegrationTest {

    @Test(expected=SearchPhaseExecutionException.class)
    public void testTwoTypes() throws Exception {
        createIndex("idx");
        client().prepareSearch("idx").setAggregations(JsonXContent.contentBuilder()
            .startObject()
                .startObject("in_stock")
                    .startObject("filter")
                        .startObject("range")
                            .startObject("stock")
                                .field("gt", 0)
                            .endObject()
                        .endObject()
                    .endObject()
                    .startObject("terms")
                        .field("field", "stock")
                    .endObject()
                .endObject()
            .endObject()).execute().actionGet();
    }

}
