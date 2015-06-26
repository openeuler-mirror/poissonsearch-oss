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
 * Parser code for MatchAllQuery
 */
public class MatchAllQueryParser extends BaseQueryParser {

    @Inject
    public MatchAllQueryParser() {
    }

    @Override
    public String[] names() {
        return new String[]{MatchAllQueryBuilder.NAME, Strings.toCamelCase(MatchAllQueryBuilder.NAME)};
    }

    @Override
    public MatchAllQueryBuilder fromXContent(QueryParseContext parseContext) throws IOException {
        XContentParser parser = parseContext.parser();

        String currentFieldName = null;
        XContentParser.Token token;
        float boost = 1.0f;
        while (((token = parser.nextToken()) != XContentParser.Token.END_OBJECT && token != XContentParser.Token.END_ARRAY)) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token.isValue()) {
                if ("boost".equals(currentFieldName)) {
                    boost = parser.floatValue();
                } else {
                    throw new QueryParsingException(parseContext, "[match_all] query does not support [" + currentFieldName + "]");
                }
            }
        }
        MatchAllQueryBuilder queryBuilder = new MatchAllQueryBuilder();
        queryBuilder.boost(boost);
        return queryBuilder;
    }

    @Override
    public MatchAllQueryBuilder getBuilderPrototype() {
        return MatchAllQueryBuilder.PROTOTYPE;
    }
}
