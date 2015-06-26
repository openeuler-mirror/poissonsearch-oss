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
 *
 */
public class BoostingQueryParser extends BaseQueryParser {

    @Inject
    public BoostingQueryParser() {
    }

    @Override
    public String[] names() {
        return new String[]{BoostingQueryBuilder.NAME};
    }

    @Override
    public QueryBuilder fromXContent(QueryParseContext parseContext) throws IOException, QueryParsingException {
        XContentParser parser = parseContext.parser();

        QueryBuilder positiveQuery = null;
        boolean positiveQueryFound = false;
        QueryBuilder negativeQuery = null;
        boolean negativeQueryFound = false;
        float boost = 1.0f;
        float negativeBoost = -1;

        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_OBJECT) {
                if ("positive".equals(currentFieldName)) {
                    positiveQuery = parseContext.parseInnerQueryBuilder();
                    positiveQueryFound = true;
                } else if ("negative".equals(currentFieldName)) {
                    negativeQuery = parseContext.parseInnerQueryBuilder();
                    negativeQueryFound = true;
                } else {
                    throw new QueryParsingException(parseContext, "[boosting] query does not support [" + currentFieldName + "]");
                }
            } else if (token.isValue()) {
                if ("negative_boost".equals(currentFieldName) || "negativeBoost".equals(currentFieldName)) {
                    negativeBoost = parser.floatValue();
                } else if ("boost".equals(currentFieldName)) {
                    boost = parser.floatValue();
                } else {
                    throw new QueryParsingException(parseContext, "[boosting] query does not support [" + currentFieldName + "]");
                }
            }
        }

        if (!positiveQueryFound) {
            throw new QueryParsingException(parseContext, "[boosting] query requires 'positive' query to be set'");
        }
        if (!negativeQueryFound) {
            throw new QueryParsingException(parseContext, "[boosting] query requires 'negative' query to be set'");
        }
        if (negativeBoost < 0) {
            throw new QueryParsingException(parseContext, "[boosting] query requires 'negative_boost' to be set to be a positive value'");
        }

        BoostingQueryBuilder boostingQuery = new BoostingQueryBuilder();
        boostingQuery.positive(positiveQuery);
        boostingQuery.negative(negativeQuery);
        boostingQuery.negativeBoost(negativeBoost);
        boostingQuery.boost(boost);
        boostingQuery.validate();
        return boostingQuery;
    }

    @Override
    public BoostingQueryBuilder getBuilderPrototype() {
        return BoostingQueryBuilder.PROTOTYPE;
    }
}