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
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.util.List;

import static com.google.common.collect.Lists.newArrayList;

/**
 * Parser for the {@link BoolQueryBuilder}
 */
public class BoolQueryParser extends BaseQueryParser {

    @Inject
    public BoolQueryParser(Settings settings) {
        BooleanQuery.setMaxClauseCount(settings.getAsInt("index.query.bool.max_clause_count", settings.getAsInt("indices.query.bool.max_clause_count", BooleanQuery.getMaxClauseCount())));
    }

    @Override
    public String[] names() {
        return new String[]{BoolQueryBuilder.NAME};
    }

    @Override
    public QueryBuilder fromXContent(QueryParseContext parseContext) throws IOException, QueryParsingException {
        XContentParser parser = parseContext.parser();

        boolean disableCoord = BoolQueryBuilder.DISABLE_COORD_DEFAULT;
        boolean adjustPureNegative = BoolQueryBuilder.ADJUST_PURE_NEGATIVE_DEFAULT;
        float boost = 1.0f;
        String minimumShouldMatch = null;

        final List<QueryBuilder> mustClauses = newArrayList();
        final List<QueryBuilder> mustNotClauses = newArrayList();
        final List<QueryBuilder> shouldClauses = newArrayList();
        final List<QueryBuilder> filterClauses = newArrayList();
        String queryName = null;

        String currentFieldName = null;
        XContentParser.Token token;
        QueryBuilder query;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (parseContext.isDeprecatedSetting(currentFieldName)) {
                // skip
            } else if (token == XContentParser.Token.START_OBJECT) {
                switch (currentFieldName) {
                case "must":
                    query = parseContext.parseInnerQueryBuilder();
                    if (query != null) {
                        mustClauses.add(query);
                    }
                    break;
                case "should":
                    query = parseContext.parseInnerQueryBuilder();
                    if (query != null) {
                        shouldClauses.add(query);
                        if (parseContext.isFilter() && minimumShouldMatch == null) {
                            minimumShouldMatch = "1";
                        }
                    }
                    break;
                case "filter":
                    query = parseContext.parseInnerFilterToQueryBuilder();
                    if (query != null) {
                        filterClauses.add(query);
                    }
                    break;
                case "must_not":
                case "mustNot":
                    query = parseContext.parseInnerFilterToQueryBuilder();
                    if (query != null) {
                        mustNotClauses.add(query);
                    }
                    break;
                default:
                    throw new QueryParsingException(parseContext, "[bool] query does not support [" + currentFieldName + "]");
                }
            } else if (token == XContentParser.Token.START_ARRAY) {
                while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                    switch (currentFieldName) {
                    case "must":
                        query = parseContext.parseInnerQueryBuilder();
                        if (query != null) {
                            mustClauses.add(query);
                        }
                        break;
                    case "should":
                        query = parseContext.parseInnerQueryBuilder();
                        if (query != null) {
                            shouldClauses.add(query);
                            if (parseContext.isFilter() && minimumShouldMatch == null) {
                                minimumShouldMatch = "1";
                            }
                        }
                        break;
                    case "filter":
                        query = parseContext.parseInnerFilterToQueryBuilder();
                        if (query != null) {
                            filterClauses.add(query);
                        }
                        break;
                    case "must_not":
                    case "mustNot":
                        query = parseContext.parseInnerFilterToQueryBuilder();
                        if (query != null) {
                            mustNotClauses.add(query);
                        }
                        break;
                    default:
                        throw new QueryParsingException(parseContext, "bool query does not support [" + currentFieldName + "]");
                    }
                }
            } else if (token.isValue()) {
                if ("disable_coord".equals(currentFieldName) || "disableCoord".equals(currentFieldName)) {
                    disableCoord = parser.booleanValue();
                } else if ("minimum_should_match".equals(currentFieldName) || "minimumShouldMatch".equals(currentFieldName)) {
                    minimumShouldMatch = parser.textOrNull();
                } else if ("boost".equals(currentFieldName)) {
                    boost = parser.floatValue();
                } else if ("minimum_number_should_match".equals(currentFieldName) || "minimumNumberShouldMatch".equals(currentFieldName)) {
                    minimumShouldMatch = parser.textOrNull();
                } else if ("adjust_pure_negative".equals(currentFieldName) || "adjustPureNegative".equals(currentFieldName)) {
                    adjustPureNegative = parser.booleanValue();
                } else if ("_name".equals(currentFieldName)) {
                    queryName = parser.text();
                } else {
                    throw new QueryParsingException(parseContext, "[bool] query does not support [" + currentFieldName + "]");
                }
            }
        }
        BoolQueryBuilder boolQuery = new BoolQueryBuilder();
        for (QueryBuilder queryBuilder : mustClauses) {
            boolQuery.must(queryBuilder);
        }
        for (QueryBuilder queryBuilder : mustNotClauses) {
            boolQuery.mustNot(queryBuilder);
        }
        for (QueryBuilder queryBuilder : shouldClauses) {
            boolQuery.should(queryBuilder);
        }
        for (QueryBuilder queryBuilder : filterClauses) {
            boolQuery.filter(queryBuilder);
        }
        boolQuery.boost(boost);
        boolQuery.disableCoord(disableCoord);
        boolQuery.adjustPureNegative(adjustPureNegative);
        boolQuery.minimumNumberShouldMatch(minimumShouldMatch);
        boolQuery.queryName(queryName);
        return boolQuery;
    }

    @Override
    public BoolQueryBuilder getBuilderPrototype() {
        return BoolQueryBuilder.PROTOTYPE;
    }
}
