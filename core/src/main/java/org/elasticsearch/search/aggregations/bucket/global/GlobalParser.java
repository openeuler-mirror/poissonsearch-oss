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
package org.elasticsearch.search.aggregations.bucket.global;

import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.search.aggregations.Aggregator;
import java.io.IOException;

/**
 *
 */
public class GlobalParser implements Aggregator.Parser {

    @Override
    public String type() {
        return InternalGlobal.TYPE.name();
    }

    @Override
    public GlobalAggregatorBuilder parse(String aggregationName, XContentParser parser, QueryParseContext context)
            throws IOException {
        parser.nextToken();
        return new GlobalAggregatorBuilder(aggregationName);
    }

    @Override
    public GlobalAggregatorBuilder getFactoryPrototypes() {
        return GlobalAggregatorBuilder.PROTOTYPE;
    }

}
