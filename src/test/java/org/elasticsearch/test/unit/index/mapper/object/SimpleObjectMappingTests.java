/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.test.unit.index.mapper.object;

import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.test.unit.index.mapper.MapperTests;
import org.testng.annotations.Test;

/**
 */
@Test
public class SimpleObjectMappingTests {

    public void testDifferentInnerObjectTokenFailure() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .endObject().endObject().string();

        DocumentMapper defaultMapper = MapperTests.newParser().parse(mapping);
        try {
            defaultMapper.parse("type", "1", new BytesArray(" {\n" +
                    "      \"object\": {\n" +
                    "        \"array\":[\n" +
                    "        {\n" +
                    "          \"object\": { \"value\": \"value\" }\n" +
                    "        },\n" +
                    "        {\n" +
                    "          \"object\":\"value\"\n" +
                    "        }\n" +
                    "        ]\n" +
                    "      },\n" +
                    "      \"value\":\"value\"\n" +
                    "    }"));
            assert false;
        } catch (MapperParsingException e) {
            // all is well
        }
    }
}
