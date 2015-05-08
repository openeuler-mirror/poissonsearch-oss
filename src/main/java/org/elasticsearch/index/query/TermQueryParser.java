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

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;

/**
 * Parser for the TermQuery.
 */
public class TermQueryParser extends BaseQueryParser {

    public static final String NAME = "term";

    @Inject
    public TermQueryParser() {
    }

    @Override
    public String[] names() {
        return new String[]{NAME};
    }

    public QueryBuilder fromXContent(QueryParseContext parseContext) throws IOException, QueryParsingException {
        XContentParser parser = parseContext.parser();

        String queryName = null;
        String fieldName = null;
        Object value = null;
        float boost = 1.0f;
        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (parseContext.isDeprecatedSetting(currentFieldName)) {
                // skip
            } else if (token == XContentParser.Token.START_OBJECT) {
                // also support a format of "term" : {"field_name" : { ... }}
                fieldName = currentFieldName;
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        currentFieldName = parser.currentName();
                    } else {
                        if ("term".equals(currentFieldName)) {
                            value = parser.objectBytes();
                        } else if ("value".equals(currentFieldName)) {
                            value = parser.objectBytes();
                        } else if ("_name".equals(currentFieldName)) {
                            queryName = parser.text();
                        } else if ("boost".equals(currentFieldName)) {
                            boost = parser.floatValue();
                        } else {
                            throw new QueryParsingException(parseContext, "[term] query does not support [" + currentFieldName + "]");
                        }
                    }
                }
            } else if (token.isValue()) {
                if ("_name".equals(currentFieldName)) {
                    queryName = parser.text();
                } else if ("boost".equals(currentFieldName)) {
                    boost = parser.floatValue();
                } else {
                    fieldName = currentFieldName;
                    value = parser.objectBytes();
                }
            }
        }

        TermQueryBuilder termQuery = new TermQueryBuilder(fieldName, value);
        if (boost != 1.0f) {
            termQuery.boost(boost);
        }
        if (queryName != null) {
            termQuery.queryName(queryName);
        }
        termQuery.validate();
        return termQuery;
    }
}
