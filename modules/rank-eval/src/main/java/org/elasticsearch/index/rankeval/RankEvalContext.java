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

package org.elasticsearch.index.rankeval;

import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.ParseFieldMatcherSupplier;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.search.aggregations.AggregatorParsers;
import org.elasticsearch.search.suggest.Suggesters;

public class RankEvalContext implements ParseFieldMatcherSupplier {

    private final ParseFieldMatcher parseFieldMatcher;
    private final AggregatorParsers aggs;
    private final Suggesters suggesters;
    private final QueryParseContext parseContext;

    public RankEvalContext(ParseFieldMatcher parseFieldMatcher, QueryParseContext parseContext, AggregatorParsers aggs,
            Suggesters suggesters) {
        this.parseFieldMatcher = parseFieldMatcher;
        this.aggs = aggs;
        this.suggesters = suggesters;
        this.parseContext = parseContext;
    }

    public Suggesters getSuggesters() {
        return this.suggesters;
    }

    public AggregatorParsers getAggs() {
        return this.aggs;
    }

    @Override
    public ParseFieldMatcher getParseFieldMatcher() {
        return this.parseFieldMatcher;
    }

    public XContentParser parser() {
        return this.parseContext.parser();
    }

    public QueryParseContext getParseContext() {
        return this.parseContext;
    }

}
