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

package org.elasticsearch.index.mapper.xcontent.typelevels;

import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.mapper.xcontent.XContentDocumentMapper;
import org.elasticsearch.index.mapper.xcontent.XContentMapperTests;
import org.testng.annotations.Test;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

/**
 * @author kimchy (shay.banon)
 */
public class ParseMappingTypeLevelTests {

    @Test public void testTypeLevel() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_source").field("enabled", false).endObject()
                .endObject().endObject().string();

        XContentDocumentMapper mapper = XContentMapperTests.newParser().parse("type", mapping);
        assertThat(mapper.type(), equalTo("type"));
        assertThat(mapper.sourceMapper().enabled(), equalTo(false));

        mapper = XContentMapperTests.newParser().parse(mapping);
        assertThat(mapper.type(), equalTo("type"));
        assertThat(mapper.sourceMapper().enabled(), equalTo(false));
    }
}
