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

package org.elasticsearch.index.mapper.json.multifield;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.analysis.AnalysisService;
import org.elasticsearch.index.mapper.json.JsonDocumentMapper;
import org.elasticsearch.index.mapper.json.JsonDocumentMapperParser;
import org.testng.annotations.Test;

import static org.elasticsearch.index.mapper.json.JsonMapperBuilders.*;
import static org.elasticsearch.util.io.Streams.*;
import static org.hamcrest.MatcherAssert.*;
import static org.hamcrest.Matchers.*;

/**
 * @author kimchy (shay.banon)
 */
@Test
public class JsonMultiFieldTests {

    @Test public void testMultiField() throws Exception {
        String mapping = copyToStringFromClasspath("/org/elasticsearch/index/mapper/json/multifield/test-mapping.json");
        JsonDocumentMapper docMapper = (JsonDocumentMapper) new JsonDocumentMapperParser(new AnalysisService(new Index("test"))).parse(mapping);
        byte[] json = copyToBytesFromClasspath("/org/elasticsearch/index/mapper/json/multifield/test-data.json");
        Document doc = docMapper.parse(json).doc();

        Field f = doc.getField("name");
        assertThat(f.name(), equalTo("name"));
        assertThat(f.stringValue(), equalTo("some name"));
        assertThat(f.isStored(), equalTo(true));
        assertThat(f.isIndexed(), equalTo(true));

        f = doc.getField("name.indexed");
        assertThat(f.name(), equalTo("name.indexed"));
        assertThat(f.stringValue(), equalTo("some name"));
        assertThat(f.isStored(), equalTo(false));
        assertThat(f.isIndexed(), equalTo(true));

        f = doc.getField("name.not_indexed");
        assertThat(f.name(), equalTo("name.not_indexed"));
        assertThat(f.stringValue(), equalTo("some name"));
        assertThat(f.isStored(), equalTo(true));
        assertThat(f.isIndexed(), equalTo(false));
    }

    @Test public void testBuildThenParse() throws Exception {
        JsonDocumentMapper builderDocMapper = doc(object("person").add(
                multiField("name")
                        .add(stringField("name").store(Field.Store.YES))
                        .add(stringField("indexed").index(Field.Index.ANALYZED))
                        .add(stringField("not_indexed").index(Field.Index.NO).store(Field.Store.YES))
        )).build();

        String builtMapping = builderDocMapper.buildSource();
//        System.out.println(builtMapping);
        // reparse it
        JsonDocumentMapper docMapper = (JsonDocumentMapper) new JsonDocumentMapperParser(new AnalysisService(new Index("test"))).parse(builtMapping);


        byte[] json = copyToBytesFromClasspath("/org/elasticsearch/index/mapper/json/multifield/test-data.json");
        Document doc = docMapper.parse(json).doc();

        Field f = doc.getField("name");
        assertThat(f.name(), equalTo("name"));
        assertThat(f.stringValue(), equalTo("some name"));
        assertThat(f.isStored(), equalTo(true));
        assertThat(f.isIndexed(), equalTo(true));

        f = doc.getField("name.indexed");
        assertThat(f.name(), equalTo("name.indexed"));
        assertThat(f.stringValue(), equalTo("some name"));
        assertThat(f.isStored(), equalTo(false));
        assertThat(f.isIndexed(), equalTo(true));

        f = doc.getField("name.not_indexed");
        assertThat(f.name(), equalTo("name.not_indexed"));
        assertThat(f.stringValue(), equalTo("some name"));
        assertThat(f.isStored(), equalTo(true));
        assertThat(f.isIndexed(), equalTo(false));
    }
}
