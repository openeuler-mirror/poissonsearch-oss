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

import org.apache.lucene.search.Query;
import org.apache.lucene.search.spans.SpanQuery;
import org.apache.lucene.search.spans.SpanWithinQuery;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;

/**
 * Parser for {@link SpanWithinQuery}
 */
public class SpanWithinQueryParser extends BaseQueryParserTemp {

    @Inject
    public SpanWithinQueryParser() {
    }

    @Override
    public String[] names() {
        return new String[]{SpanWithinQueryBuilder.NAME, Strings.toCamelCase(SpanWithinQueryBuilder.NAME)};
    }

    @Override
    public Query parse(QueryParseContext parseContext) throws IOException, QueryParsingException {
        XContentParser parser = parseContext.parser();

        float boost = 1.0f;
        String queryName = null;
        SpanQuery big = null;
        SpanQuery little = null;

        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_OBJECT) {
                if ("big".equals(currentFieldName)) {
                    Query query = parseContext.parseInnerQuery();
                    if (query instanceof SpanQuery == false) {
                        throw new QueryParsingException(parseContext, "span_within [big] must be of type span query");
                    }
                    big = (SpanQuery) query;
                } else if ("little".equals(currentFieldName)) {
                    Query query = parseContext.parseInnerQuery();
                    if (query instanceof SpanQuery == false) {
                        throw new QueryParsingException(parseContext, "span_within [little] must be of type span query");
                    }
                    little = (SpanQuery) query;
                } else {
                    throw new QueryParsingException(parseContext, "[span_within] query does not support [" + currentFieldName + "]");
                }
            } else if ("boost".equals(currentFieldName)) {
                boost = parser.floatValue();
            } else if ("_name".equals(currentFieldName)) {
                queryName = parser.text();
            } else {
                throw new QueryParsingException(parseContext, "[span_within] query does not support [" + currentFieldName + "]");
            }
        }

        if (big == null) {
            throw new QueryParsingException(parseContext, "span_within must include [big]");
        }
        if (little == null) {
            throw new QueryParsingException(parseContext, "span_within must include [little]");
        }

        Query query = new SpanWithinQuery(big, little);
        query.setBoost(boost);
        if (queryName != null) {
            parseContext.addNamedQuery(queryName, query);
        }
        return query;
    }
}
