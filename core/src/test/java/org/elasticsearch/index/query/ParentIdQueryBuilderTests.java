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

package org.elasticsearch.index.query;

import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocValuesTermsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.elasticsearch.action.admin.indices.mapping.put.PutMappingRequest;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.index.fielddata.IndexFieldDataService;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.internal.TypeFieldMapper;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.test.TestSearchContext;
import org.hamcrest.Matchers;

import java.io.IOException;

public class ParentIdQueryBuilderTests extends AbstractQueryTestCase<ParentIdQueryBuilder> {

    protected static final String PARENT_TYPE = "parent";
    protected static final String CHILD_TYPE = "child";

    @Override
    public void setUp() throws Exception {
        super.setUp();
        MapperService mapperService = queryShardContext().getMapperService();
        mapperService.merge(PARENT_TYPE, new CompressedXContent(PutMappingRequest.buildFromSimplifiedDef(PARENT_TYPE,
            STRING_FIELD_NAME, "type=text",
            INT_FIELD_NAME, "type=integer",
            DOUBLE_FIELD_NAME, "type=double",
            BOOLEAN_FIELD_NAME, "type=boolean",
            DATE_FIELD_NAME, "type=date",
            OBJECT_FIELD_NAME, "type=object"
        ).string()), MapperService.MergeReason.MAPPING_UPDATE, false);
        mapperService.merge(CHILD_TYPE, new CompressedXContent(PutMappingRequest.buildFromSimplifiedDef(CHILD_TYPE,
            "_parent", "type=" + PARENT_TYPE,
            STRING_FIELD_NAME, "type=text",
            INT_FIELD_NAME, "type=integer",
            DOUBLE_FIELD_NAME, "type=double",
            BOOLEAN_FIELD_NAME, "type=boolean",
            DATE_FIELD_NAME, "type=date",
            OBJECT_FIELD_NAME, "type=object"
        ).string()), MapperService.MergeReason.MAPPING_UPDATE, false);
    }

    @Override
    protected void setSearchContext(String[] types) {
        final MapperService mapperService = queryShardContext().getMapperService();
        final IndexFieldDataService fieldData = indexFieldDataService();
        TestSearchContext testSearchContext = new TestSearchContext(queryShardContext()) {

            @Override
            public MapperService mapperService() {
                return mapperService; // need to build / parse inner hits sort fields
            }

            @Override
            public IndexFieldDataService fieldData() {
                return fieldData; // need to build / parse inner hits sort fields
            }
        };
        testSearchContext.getQueryShardContext().setTypes(types);
        SearchContext.setCurrent(testSearchContext);
    }

    @Override
    protected ParentIdQueryBuilder doCreateTestQueryBuilder() {
        return new ParentIdQueryBuilder(CHILD_TYPE, randomAsciiOfLength(4));
    }

    @Override
    protected void doAssertLuceneQuery(ParentIdQueryBuilder queryBuilder, Query query, QueryShardContext context) throws IOException {
        assertThat(query, Matchers.instanceOf(BooleanQuery.class));
        BooleanQuery booleanQuery = (BooleanQuery) query;
        assertThat(booleanQuery.clauses().size(), Matchers.equalTo(2));
        DocValuesTermsQuery idQuery = (DocValuesTermsQuery) booleanQuery.clauses().get(0).getQuery();
        // there are no getters to get the field and terms on DocValuesTermsQuery, so lets validate by creating a
        // new query based on the builder:
        assertThat(idQuery, Matchers.equalTo(new DocValuesTermsQuery("_parent#" + PARENT_TYPE, queryBuilder.getId())));

        TermQuery typeQuery = (TermQuery) booleanQuery.clauses().get(1).getQuery();
        assertThat(typeQuery.getTerm().field(), Matchers.equalTo(TypeFieldMapper.NAME));
        assertThat(typeQuery.getTerm().text(), Matchers.equalTo(queryBuilder.getType()));
    }

    public void testFromJson() throws IOException {
        String query =
            "{\n" +
                "  \"parent_id\" : {\n" +
                "    \"type\" : \"child\",\n" +
                "    \"id\" : \"123\",\n" +
                "    \"boost\" : 3.0,\n" +
                "    \"_name\" : \"name\"" +
                "  }\n" +
                "}";
        ParentIdQueryBuilder queryBuilder = (ParentIdQueryBuilder) parseQuery(query);
        checkGeneratedJson(query, queryBuilder);
        assertThat(queryBuilder.getType(), Matchers.equalTo("child"));
        assertThat(queryBuilder.getId(), Matchers.equalTo("123"));
        assertThat(queryBuilder.boost(), Matchers.equalTo(3f));
        assertThat(queryBuilder.queryName(), Matchers.equalTo("name"));
    }

}
