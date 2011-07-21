/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
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

import org.apache.lucene.search.Filter;
import org.apache.lucene.search.Query;
import org.elasticsearch.ElasticSearchIllegalStateException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.search.function.FiltersFunctionScoreQuery;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.script.SearchScript;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

/**
 * @author kimchy (shay.banon)
 */
public class CustomFiltersScoreQueryParser implements QueryParser {

    public static final String NAME = "custom_filters_score";

    @Inject public CustomFiltersScoreQueryParser() {
    }

    @Override public String[] names() {
        return new String[]{NAME, Strings.toCamelCase(NAME)};
    }

    @Override public Query parse(QueryParseContext parseContext) throws IOException, QueryParsingException {
        XContentParser parser = parseContext.parser();

        Query query = null;
        float boost = 1.0f;
        String scriptLang = null;
        Map<String, Object> vars = null;

        ArrayList<Filter> filters = new ArrayList<Filter>();
        ArrayList<String> scripts = new ArrayList<String>();

        String currentFieldName = null;
        XContentParser.Token token;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_OBJECT) {
                if ("query".equals(currentFieldName)) {
                    query = parseContext.parseInnerQuery();
                } else if ("params".equals(currentFieldName)) {
                    vars = parser.map();
                }
            } else if (token == XContentParser.Token.START_ARRAY) {
                if ("filters".equals(currentFieldName)) {
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        String script = null;
                        Filter filter = null;
                        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
                            if (token == XContentParser.Token.FIELD_NAME) {
                                currentFieldName = parser.currentName();
                            } else if (token == XContentParser.Token.START_OBJECT) {
                                if ("filter".equals(currentFieldName)) {
                                    filter = parseContext.parseInnerFilter();
                                }
                            } else if (token.isValue()) {
                                if ("script".equals(currentFieldName)) {
                                    script = parser.text();
                                }
                            }
                        }
                        if (script == null) {
                            throw new QueryParsingException(parseContext.index(), "[custom_filters_score] missing 'script' in filters array element");
                        }
                        if (filter == null) {
                            throw new QueryParsingException(parseContext.index(), "[custom_filters_score] missing 'filter' in filters array element");
                        }
                        filters.add(filter);
                        scripts.add(script);
                    }
                }
            } else if (token.isValue()) {
                if ("lang".equals(currentFieldName)) {
                    scriptLang = parser.text();
                } else if ("boost".equals(currentFieldName)) {
                    boost = parser.floatValue();
                }
            }
        }
        if (query == null) {
            throw new QueryParsingException(parseContext.index(), "[custom_filters_score] requires 'query' field");
        }
        if (filters.isEmpty()) {
            throw new QueryParsingException(parseContext.index(), "[custom_filters_score] requires 'filters' field");
        }

        SearchContext context = SearchContext.current();
        if (context == null) {
            throw new ElasticSearchIllegalStateException("No search context on going...");
        }
        FiltersFunctionScoreQuery.FilterFunction[] filterFunctions = new FiltersFunctionScoreQuery.FilterFunction[filters.size()];
        for (int i = 0; i < filterFunctions.length; i++) {
            SearchScript searchScript = context.scriptService().search(context.lookup(), scriptLang, scripts.get(i), vars);
            filterFunctions[i] = new FiltersFunctionScoreQuery.FilterFunction(filters.get(i), new CustomScoreQueryParser.ScriptScoreFunction(searchScript));
        }
        FiltersFunctionScoreQuery functionScoreQuery = new FiltersFunctionScoreQuery(query, filterFunctions);
        functionScoreQuery.setBoost(boost);
        return functionScoreQuery;
    }
}