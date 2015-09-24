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

import java.io.IOException;

/**
 * Parser for query filter
 * @deprecated use any query instead directly, possible since queries and filters are merged.
 */
// TODO: remove when https://github.com/elastic/elasticsearch/issues/13326 is fixed
@Deprecated
public class QueryFilterParser implements QueryParser<QueryFilterBuilder> {

    @Override
    public String[] names() {
        return new String[]{QueryFilterBuilder.NAME};
    }

    @Override
    public QueryFilterBuilder fromXContent(QueryParseContext parseContext) throws IOException {
        return new QueryFilterBuilder(parseContext.parseInnerQueryBuilder());
    }

    @Override
    public QueryFilterBuilder getBuilderPrototype() {
        return QueryFilterBuilder.PROTOTYPE;
    }
}