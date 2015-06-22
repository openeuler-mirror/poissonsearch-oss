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
 * Parses the json representation of a spantermquery into the Elasticsearch internal
 * query builder representation.
 */
public class SpanTermQueryParser extends BaseQueryParser {

    @Inject
    public SpanTermQueryParser() {
    }

    @Override
    public String[] names() {
        return new String[]{SpanTermQueryBuilder.NAME, Strings.toCamelCase(SpanTermQueryBuilder.NAME)};
    }

    @Override
    public QueryBuilder fromXContent(QueryParseContext parseContext) throws IOException, QueryParsingException {
        XContentParser parser = parseContext.parser();

        XContentParser.Token token = parser.currentToken();
        if (token == XContentParser.Token.START_OBJECT) {
            token = parser.nextToken();
        }

        assert token == XContentParser.Token.FIELD_NAME;
        String fieldName = parser.currentName();


        Object value = null;
        float boost = 1.0f;
        String queryName = null;
        token = parser.nextToken();
        if (token == XContentParser.Token.START_OBJECT) {
            String currentFieldName = null;
            while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                if (token == XContentParser.Token.FIELD_NAME) {
                    currentFieldName = parser.currentName();
                } else {
                    if ("term".equals(currentFieldName)) {
                        value = parser.objectBytes();
                    } else if ("value".equals(currentFieldName)) {
                        value = parser.objectBytes();
                    } else if ("boost".equals(currentFieldName)) {
                        boost = parser.floatValue();
                    } else if ("_name".equals(currentFieldName)) {
                        queryName = parser.text();
                    } else {
                        throw new QueryParsingException(parseContext, "[span_term] query does not support [" + currentFieldName + "]");
                    }
                }
            }
            parser.nextToken();
        } else {
            value = parser.objectBytes();
            // move to the next token
            parser.nextToken();
        }

        if (value == null) {
            throw new QueryParsingException(parseContext, "No value specified for term query");
        }

        SpanTermQueryBuilder result = new SpanTermQueryBuilder(fieldName, value);
        result.boost(boost).queryName(queryName);
        result.validate();
        return result;
    }

    @Override
    public SpanTermQueryBuilder getBuilderPrototype() {
        return SpanTermQueryBuilder.PROTOTYPE;
    }
}
