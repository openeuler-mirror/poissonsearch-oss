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

import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.spans.SpanTermQuery;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.lucene.BytesRefs;
import org.elasticsearch.index.mapper.MapperService;

/**
 * A Span Query that matches documents containing a term.
 * @see SpanTermQuery
 */
public class SpanTermQueryBuilder extends BaseTermQueryBuilder<SpanTermQueryBuilder> implements SpanQueryBuilder {
    /** @see BaseTermQueryBuilder#BaseTermQueryBuilder(String, String) */
    public SpanTermQueryBuilder(String name, String value) {
        super(name, (Object) value);
    }
    /** @see BaseTermQueryBuilder#BaseTermQueryBuilder(String, int) */
    public SpanTermQueryBuilder(String name, int value) {
        super(name, (Object) value);
    }
    /** @see BaseTermQueryBuilder#BaseTermQueryBuilder(String, long) */
    public SpanTermQueryBuilder(String name, long value) {
        super(name, (Object) value);
    }
    /** @see BaseTermQueryBuilder#BaseTermQueryBuilder(String, float) */
    public SpanTermQueryBuilder(String name, float value) {
        super(name, (Object) value);
    }
    /** @see BaseTermQueryBuilder#BaseTermQueryBuilder(String, double) */
    public SpanTermQueryBuilder(String name, double value) {
        super(name, (Object) value);
    }
    /** @see BaseTermQueryBuilder#BaseTermQueryBuilder(String, Object) */
    public SpanTermQueryBuilder(String name, Object value) {
        super(name, value);
    }

    public SpanTermQueryBuilder() {
        // for testing and serialisation only
    }

    @Override
    protected String parserName() {
        return SpanTermQueryParser.NAME;
    }

    @Override
    public Query toQuery(QueryParseContext context) {
        BytesRef valueBytes = null;
        MapperService.SmartNameFieldMappers smartNameFieldMappers = context.smartFieldMappers(fieldName);
        if (smartNameFieldMappers != null) {
            if (smartNameFieldMappers.hasMapper()) {
                fieldName = smartNameFieldMappers.mapper().names().indexName();
                valueBytes = smartNameFieldMappers.mapper().indexedValueForSearch(value);
            }
        }
        if (valueBytes == null) {
            valueBytes = BytesRefs.toBytesRef(this.value);
        }
        
        SpanTermQuery query = new SpanTermQuery(new Term(fieldName, valueBytes));
        query.setBoost(boost);
        if (queryName != null) {
            context.addNamedQuery(queryName, query);
        }
        return query;
    }
}
