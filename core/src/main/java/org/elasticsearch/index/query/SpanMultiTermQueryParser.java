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

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;

/**
 *
 */
public class SpanMultiTermQueryParser extends BaseQueryParser {

    public static final String MATCH_NAME = "match";

    @Inject
    public SpanMultiTermQueryParser() {
    }

    @Override
    public String[] names() {
        return new String[]{SpanMultiTermQueryBuilder.NAME, Strings.toCamelCase(SpanMultiTermQueryBuilder.NAME)};
    }

    @Override
    public QueryBuilder fromXContent(QueryParseContext parseContext) throws IOException, QueryParsingException {
        XContentParser parser = parseContext.parser();
        String currentFieldName = null;
        MultiTermQueryBuilder subQuery = null;
        String queryName = null;
        float boost = AbstractQueryBuilder.DEFAULT_BOOST;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_OBJECT) {
                if (MATCH_NAME.equals(currentFieldName)) {
                    QueryBuilder innerQuery = parseContext.parseInnerQueryBuilder();
                    if (innerQuery instanceof MultiTermQueryBuilder == false) {
                        throw new QueryParsingException(parseContext, "[span_multi] [" + MATCH_NAME + "] must be of type multi term query");
                    }
                    subQuery = (MultiTermQueryBuilder) innerQuery;
                } else {
                    throw new QueryParsingException(parseContext, "[span_multi] query does not support [" + currentFieldName + "]");
                }
            } else if (token.isValue()) {
                if ("_name".equals(currentFieldName)) {
                    queryName = parser.text();
                } else if ("boost".equals(currentFieldName)) {
                    boost = parser.floatValue();
                } else {
                    throw new QueryParsingException(parseContext, "[span_multi] query does not support [" + currentFieldName + "]");
                }
            }
        }

        if (subQuery == null) {
            throw new QueryParsingException(parseContext, "[span_multi] must have [" + MATCH_NAME + "] multi term query clause");
        }

        return new SpanMultiTermQueryBuilder(subQuery).queryName(queryName).boost(boost);
    }

    @Override
    public SpanMultiTermQueryBuilder getBuilderPrototype() {
        return SpanMultiTermQueryBuilder.PROTOTYPE;
    }
}
