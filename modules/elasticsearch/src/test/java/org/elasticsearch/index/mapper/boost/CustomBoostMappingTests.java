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

package org.elasticsearch.index.mapper.boost;

import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperTests;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.testng.annotations.Test;

import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

@Test
public class CustomBoostMappingTests {

    @Test public void testCustomBoostValues() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type").startObject("properties")
                .startObject("s_field").field("type", "string").endObject()
                .startObject("l_field").field("type", "long").endObject()
                .startObject("i_field").field("type", "integer").endObject()
                .startObject("sh_field").field("type", "short").endObject()
                .startObject("b_field").field("type", "byte").endObject()
                .startObject("d_field").field("type", "double").endObject()
                .startObject("f_field").field("type", "float").endObject()
                .startObject("date_field").field("type", "date").endObject()
                .endObject().endObject().endObject().string();

        DocumentMapper mapper = MapperTests.newParser().parse(mapping);

        ParsedDocument doc = mapper.parse("type", "1", XContentFactory.jsonBuilder().startObject()
                .startObject("s_field").field("value", "s_value").field("boost", 2.0f).endObject()
                .startObject("l_field").field("value", 1l).field("boost", 3.0f).endObject()
                .startObject("i_field").field("value", 1).field("boost", 4.0f).endObject()
                .startObject("sh_field").field("value", 1).field("boost", 5.0f).endObject()
                .startObject("b_field").field("value", 1).field("boost", 6.0f).endObject()
                .startObject("d_field").field("value", 1).field("boost", 7.0f).endObject()
                .startObject("f_field").field("value", 1).field("boost", 8.0f).endObject()
                .startObject("date_field").field("value", "20100101").field("boost", 9.0f).endObject()
                .endObject().copiedBytes());

        assertThat(doc.masterDoc().getFieldable("s_field").getBoost(), equalTo(2.0f));
        assertThat(doc.masterDoc().getFieldable("l_field").getBoost(), equalTo(3.0f));
        assertThat(doc.masterDoc().getFieldable("i_field").getBoost(), equalTo(4.0f));
        assertThat(doc.masterDoc().getFieldable("sh_field").getBoost(), equalTo(5.0f));
        assertThat(doc.masterDoc().getFieldable("b_field").getBoost(), equalTo(6.0f));
        assertThat(doc.masterDoc().getFieldable("d_field").getBoost(), equalTo(7.0f));
        assertThat(doc.masterDoc().getFieldable("f_field").getBoost(), equalTo(8.0f));
        assertThat(doc.masterDoc().getFieldable("date_field").getBoost(), equalTo(9.0f));
    }
}