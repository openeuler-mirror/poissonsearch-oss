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

package org.elasticsearch.test.unit.index.mapper.dynamictemplate.pathmatch;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.index.field.data.strings.StringFieldDataType;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.FieldMappers;
import org.elasticsearch.test.unit.index.mapper.MapperTests;
import org.testng.annotations.Test;

import static org.elasticsearch.common.io.Streams.copyToBytesFromClasspath;
import static org.elasticsearch.common.io.Streams.copyToStringFromClasspath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

/**
 *
 */
public class PathMatchDynamicTemplateTests {

    @Test
    public void testSimple() throws Exception {
        String mapping = copyToStringFromClasspath("/org/elasticsearch/test/unit/index/mapper/dynamictemplate/pathmatch/test-mapping.json");
        DocumentMapper docMapper = MapperTests.newParser().parse(mapping);
        byte[] json = copyToBytesFromClasspath("/org/elasticsearch/test/unit/index/mapper/dynamictemplate/pathmatch/test-data.json");
        Document doc = docMapper.parse(new BytesArray(json)).rootDoc();

        IndexableField f = doc.getField("name");
        assertThat(f.name(), equalTo("name"));
        assertThat(f.stringValue(), equalTo("top_level"));
        assertThat(f.fieldType().stored(), equalTo(false));

        FieldMappers fieldMappers = docMapper.mappers().fullName("name");
        assertThat(fieldMappers.mappers().size(), equalTo(1));
        assertThat(fieldMappers.mapper().fieldType().stored(), equalTo(false));

        f = doc.getField("obj1.name");
        assertThat(f.name(), equalTo("obj1.name"));
        assertThat(f.fieldType().stored(), equalTo(true));

        fieldMappers = docMapper.mappers().fullName("obj1.name");
        assertThat(fieldMappers.mappers().size(), equalTo(1));
        assertThat(fieldMappers.mapper().fieldType().stored(), equalTo(true));

        f = doc.getField("obj1.obj2.name");
        assertThat(f.name(), equalTo("obj1.obj2.name"));
        assertThat(f.fieldType().stored(), equalTo(false));

        fieldMappers = docMapper.mappers().fullName("obj1.obj2.name");
        assertThat(fieldMappers.mappers().size(), equalTo(1));
        assertThat(fieldMappers.mapper().fieldType().stored(), equalTo(false));

        // verify more complex path_match expressions

        fieldMappers = docMapper.mappers().fullName("obj3.obj4.prop1");
        assertThat(fieldMappers.mappers().size(), equalTo(1));
        assertThat(fieldMappers.mapper().fieldDataType(), instanceOf(StringFieldDataType.class));
    }
}
