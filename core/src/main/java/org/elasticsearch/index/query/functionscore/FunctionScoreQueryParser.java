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

package org.elasticsearch.index.query.functionscore;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.lucene.search.function.*;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.*;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Parser for function_score query
 */
public class FunctionScoreQueryParser implements QueryParser {

    public static final String NAME = "function_score";

    // For better readability of error message
    static final String MISPLACED_FUNCTION_MESSAGE_PREFIX = "you can either define [functions] array or a single function, not both. ";

    public static final ParseField WEIGHT_FIELD = new ParseField("weight");
    private static final ParseField FILTER_FIELD = new ParseField("filter").withAllDeprecated("query");

    ScoreFunctionParserMapper functionParserMapper;

    @Inject
    public FunctionScoreQueryParser(ScoreFunctionParserMapper functionParserMapper) {
        this.functionParserMapper = functionParserMapper;
    }

    @Override
    public String[] names() {
        return new String[] { NAME, Strings.toCamelCase(NAME) };
    }

    private static final ImmutableMap<String, CombineFunction> combineFunctionsMap;

    static {
        CombineFunction[] values = CombineFunction.values();
        Builder<String, CombineFunction> combineFunctionMapBuilder = ImmutableMap.builder();
        for (CombineFunction combineFunction : values) {
            combineFunctionMapBuilder.put(combineFunction.getName(), combineFunction);
        }
        combineFunctionsMap = combineFunctionMapBuilder.build();
    }

    @Override
    public Query parse(QueryShardContext context) throws IOException {
        QueryParseContext parseContext = context.parseContext();
        XContentParser parser = parseContext.parser();

        Query query = null;
        Query filter = null;
        float boost = AbstractQueryBuilder.DEFAULT_BOOST;
        String queryName = null;

        FiltersFunctionScoreQuery.ScoreMode scoreMode = FiltersFunctionScoreQuery.ScoreMode.Multiply;
        ArrayList<FiltersFunctionScoreQuery.FilterFunction> filterFunctions = new ArrayList<>();
        Float maxBoost = null;
        Float minScore = null;

        String currentFieldName = null;
        XContentParser.Token token;
        CombineFunction combineFunction = CombineFunction.MULT;
        // Either define array of functions and filters or only one function
        boolean functionArrayFound = false;
        boolean singleFunctionFound = false;
        String singleFunctionName = null;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if ("query".equals(currentFieldName)) {
                query = parseContext.parseInnerQuery();
            } else if (parseContext.parseFieldMatcher().match(currentFieldName, FILTER_FIELD)) {
                filter = parseContext.parseInnerFilter();
            } else if ("score_mode".equals(currentFieldName) || "scoreMode".equals(currentFieldName)) {
                scoreMode = parseScoreMode(parseContext, parser);
            } else if ("boost_mode".equals(currentFieldName) || "boostMode".equals(currentFieldName)) {
                combineFunction = parseBoostMode(parseContext, parser);
            } else if ("max_boost".equals(currentFieldName) || "maxBoost".equals(currentFieldName)) {
                maxBoost = parser.floatValue();
            } else if ("boost".equals(currentFieldName)) {
                boost = parser.floatValue();
            } else if ("_name".equals(currentFieldName)) {
                queryName = parser.text();
            } else if ("min_score".equals(currentFieldName) || "minScore".equals(currentFieldName)) {
                minScore = parser.floatValue();
            } else if ("functions".equals(currentFieldName)) {
                if (singleFunctionFound) {
                    String errorString = "already found [" + singleFunctionName + "], now encountering [functions].";
                    handleMisplacedFunctionsDeclaration(errorString);
                }
                currentFieldName = parseFiltersAndFunctions(context, parser, filterFunctions, currentFieldName);
                functionArrayFound = true;
            } else {
                ScoreFunction scoreFunction;
                if (currentFieldName.equals("weight")) {
                    scoreFunction = new WeightFactorFunction(parser.floatValue());

                } else {
                    // we try to parse a score function. If there is no score
                    // function for the current field name,
                    // functionParserMapper.get() will throw an Exception.
                    scoreFunction = functionParserMapper.get(parseContext, currentFieldName).parse(context, parser);
                }
                if (functionArrayFound) {
                    String errorString = "already found [functions] array, now encountering [" + currentFieldName + "].";
                    handleMisplacedFunctionsDeclaration(errorString);
                }
                if (filterFunctions.size() > 0) {
                    throw new ElasticsearchParseException("failed to parse [{}] query. already found function [{}], now encountering [{}]. use [functions] array if you want to define several functions.", NAME, singleFunctionName, currentFieldName);
                }
                filterFunctions.add(new FiltersFunctionScoreQuery.FilterFunction(null, scoreFunction));
                singleFunctionFound = true;
                singleFunctionName = currentFieldName;
            }
        }
        if (query == null && filter == null) {
            query = Queries.newMatchAllQuery();
        } else if (query == null && filter != null) {
            query = new ConstantScoreQuery(filter);
        } else if (query != null && filter != null) {
            final BooleanQuery.Builder filtered = new BooleanQuery.Builder();
            filtered.add(query, Occur.MUST);
            filtered.add(filter, Occur.FILTER);
            query = filtered.build();
        }
        // if all filter elements returned null, just use the query
        if (filterFunctions.isEmpty() && combineFunction == null) {
            return query;
        }
        if (maxBoost == null) {
            maxBoost = Float.MAX_VALUE;
        }
        Query result;
        // handle cases where only one score function and no filter was
        // provided. In this case we create a FunctionScoreQuery.
        if (filterFunctions.size() == 0 || filterFunctions.size() == 1 && (filterFunctions.get(0).filter == null || Queries.isConstantMatchAllQuery(filterFunctions.get(0).filter))) {
            ScoreFunction function = filterFunctions.size() == 0 ? null : filterFunctions.get(0).function;
            FunctionScoreQuery theQuery = new FunctionScoreQuery(query, function, minScore);
            if (combineFunction != null) {
                theQuery.setCombineFunction(combineFunction);
            }
            theQuery.setMaxBoost(maxBoost);
            result = theQuery;
            // in all other cases we create a FiltersFunctionScoreQuery.
        } else {
            FiltersFunctionScoreQuery functionScoreQuery = new FiltersFunctionScoreQuery(query, scoreMode,
                    filterFunctions.toArray(new FiltersFunctionScoreQuery.FilterFunction[filterFunctions.size()]), maxBoost, minScore);
            if (combineFunction != null) {
                functionScoreQuery.setCombineFunction(combineFunction);
            }
            result = functionScoreQuery;
        }
        result.setBoost(boost);
        if (queryName != null) {
            context.addNamedQuery(queryName, query);
        }
        return result;
    }

    private void handleMisplacedFunctionsDeclaration(String errorString) {
        throw new ElasticsearchParseException("failed to parse [{}] query. [{}]", NAME, MISPLACED_FUNCTION_MESSAGE_PREFIX + errorString);
    }

    private String parseFiltersAndFunctions(QueryShardContext context, XContentParser parser,
                                            ArrayList<FiltersFunctionScoreQuery.FilterFunction> filterFunctions, String currentFieldName) throws IOException {
        QueryParseContext parseContext = context.parseContext();
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
            Query filter = null;
            ScoreFunction scoreFunction = null;
            Float functionWeight = null;
            if (token != XContentParser.Token.START_OBJECT) {
                throw new ParsingException(parseContext, "failed to parse [{}]. malformed query, expected a [{}] while parsing functions but got a [{}] instead", XContentParser.Token.START_OBJECT, token, NAME);
            } else {
                while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                    if (token == XContentParser.Token.FIELD_NAME) {
                        currentFieldName = parser.currentName();
                    } else if (parseContext.parseFieldMatcher().match(currentFieldName, WEIGHT_FIELD)) {
                        functionWeight = parser.floatValue();
                    } else {
                        if ("filter".equals(currentFieldName)) {
                            filter = parseContext.parseInnerFilter();
                        } else {
                            // do not need to check null here,
                            // functionParserMapper throws exception if parser
                            // non-existent
                            ScoreFunctionParser functionParser = functionParserMapper.get(parseContext, currentFieldName);
                            scoreFunction = functionParser.parse(context, parser);
                        }
                    }
                }
                if (functionWeight != null) {
                    scoreFunction = new WeightFactorFunction(functionWeight, scoreFunction);
                }
            }
            if (filter == null) {
                filter = Queries.newMatchAllQuery();
            }
            if (scoreFunction == null) {
                throw new ElasticsearchParseException("failed to parse [{}] query. an entry in functions list is missing a function.", NAME);
            }
            filterFunctions.add(new FiltersFunctionScoreQuery.FilterFunction(filter, scoreFunction));

        }
        return currentFieldName;
    }

    private FiltersFunctionScoreQuery.ScoreMode parseScoreMode(QueryParseContext parseContext, XContentParser parser) throws IOException {
        String scoreMode = parser.text();
        if ("avg".equals(scoreMode)) {
            return FiltersFunctionScoreQuery.ScoreMode.Avg;
        } else if ("max".equals(scoreMode)) {
            return FiltersFunctionScoreQuery.ScoreMode.Max;
        } else if ("min".equals(scoreMode)) {
            return FiltersFunctionScoreQuery.ScoreMode.Min;
        } else if ("sum".equals(scoreMode)) {
            return FiltersFunctionScoreQuery.ScoreMode.Sum;
        } else if ("multiply".equals(scoreMode)) {
            return FiltersFunctionScoreQuery.ScoreMode.Multiply;
        } else if ("first".equals(scoreMode)) {
            return FiltersFunctionScoreQuery.ScoreMode.First;
        } else {
            throw new ParsingException(parseContext, "failed to parse [{}] query. illegal score_mode [{}]", NAME, scoreMode);
        }
    }

    private CombineFunction parseBoostMode(QueryParseContext parseContext, XContentParser parser) throws IOException {
        String boostMode = parser.text();
        CombineFunction cf = combineFunctionsMap.get(boostMode);
        if (cf == null) {
            throw new ParsingException(parseContext, "failed to parse [{}] query. illegal boost_mode [{}]", NAME, boostMode);
        }
        return cf;
    }

    //norelease to be removed once all queries are moved over to extend BaseQueryParser
    @Override
    public QueryBuilder fromXContent(QueryParseContext parseContext) throws IOException {
        Query query = parse(parseContext.shardContext());
        return new QueryWrappingQueryBuilder(query);
    }

    @Override
    public FunctionScoreQueryBuilder getBuilderPrototype() {
        return FunctionScoreQueryBuilder.PROTOTYPE;
    }
}
