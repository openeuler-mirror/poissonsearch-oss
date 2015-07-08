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
 * @deprecated Use {@link BoolQueryParser} instead.
 */

@Deprecated
public class FilteredQueryParser extends BaseQueryParser {

    @Inject
    public FilteredQueryParser() {
    }

    @Override
    public String[] names() {
        return new String[]{FilteredQueryBuilder.NAME};
    }

    @Override
    public QueryBuilder fromXContent(QueryParseContext parseContext) throws IOException, QueryParsingException {
        XContentParser parser = parseContext.parser();

        QueryBuilder query = null;
        QueryBuilder filter = null;
        float boost = AbstractQueryBuilder.DEFAULT_BOOST;
        String queryName = null;

        String currentFieldName = null;
        XContentParser.Token token;

        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (parseContext.isDeprecatedSetting(currentFieldName)) {
                // skip
            } else if (token == XContentParser.Token.START_OBJECT) {
                if ("query".equals(currentFieldName)) {
                    query = parseContext.parseInnerQueryBuilder();
                } else if ("filter".equals(currentFieldName)) {
                    filter = parseContext.parseInnerFilterToQueryBuilder();
                } else {
                    throw new QueryParsingException(parseContext, "[filtered] query does not support [" + currentFieldName + "]");
                }
            } else if (token.isValue()) {
                if ("strategy".equals(currentFieldName)) {
                    // ignore
                } else if ("_name".equals(currentFieldName)) {
                    queryName = parser.text();
                } else if ("boost".equals(currentFieldName)) {
                    boost = parser.floatValue();
                } else {
                    throw new QueryParsingException(parseContext, "[filtered] query does not support [" + currentFieldName + "]");
                }
            }
        }

        FilteredQueryBuilder qb = new FilteredQueryBuilder(query, filter);
        qb.boost(boost);
        qb.queryName(queryName);
        return qb;
    }

    @Override
    public FilteredQueryBuilder getBuilderPrototype() {
        return FilteredQueryBuilder.PROTOTYPE;
    }

}
