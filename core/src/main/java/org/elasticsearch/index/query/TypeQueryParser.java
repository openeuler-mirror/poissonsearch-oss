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
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.internal.TypeFieldMapper;

import java.io.IOException;

public class TypeQueryParser extends BaseQueryParserTemp {

    @Inject
    public TypeQueryParser() {
    }

    @Override
    public String[] names() {
        return new String[]{TypeQueryBuilder.NAME};
    }

    @Override
    public Query parse(QueryParseContext parseContext) throws IOException, QueryParsingException {
        XContentParser parser = parseContext.parser();
        String queryName = null;
        float boost = AbstractQueryBuilder.DEFAULT_BOOST;
        BytesRef type = null;
        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token.isValue()) {
                if ("_name".equals(currentFieldName)) {
                    queryName = parser.text();
                } else if ("boost".equals(currentFieldName)) {
                    boost = parser.floatValue();
                } else if ("value".equals(currentFieldName)) {
                    type = parser.utf8Bytes();
                }
            } else {
                throw new QueryParsingException(parseContext, "[type] filter doesn't support [" + currentFieldName + "]");
            }
        }

        if (type == null) {
            throw new QueryParsingException(parseContext, "[type] filter needs to be provided with a value for the type");
        }

        Query filter;
        //LUCENE 4 UPGRADE document mapper should use bytesref as well?
        DocumentMapper documentMapper = parseContext.mapperService().documentMapper(type.utf8ToString());
        if (documentMapper == null) {
            filter = new TermQuery(new Term(TypeFieldMapper.NAME, type));
        } else {
            filter = documentMapper.typeFilter();
        }
        if (queryName != null) {
            parseContext.addNamedQuery(queryName, filter);
        }
        if (filter != null) {
            filter.setBoost(boost);
        }
        return filter;
    }

    @Override
    public TypeQueryBuilder getBuilderPrototype() {
        return TypeQueryBuilder.PROTOTYPE;
    }
}