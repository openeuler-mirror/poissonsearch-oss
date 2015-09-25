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

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.indices.cache.query.terms.TermsLookup;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for terms query and terms lookup.
 *
 * Filters documents that have fields that match any of the provided terms (not analyzed)
 *
 * It also supports a terms lookup mechanism which can be used to fetch the term values from
 * a document in an index.
 */
public class TermsQueryParser implements QueryParser {

    private static final ParseField MIN_SHOULD_MATCH_FIELD = new ParseField("min_match", "min_should_match", "minimum_should_match")
            .withAllDeprecated("Use [bool] query instead");
    private static final ParseField DISABLE_COORD_FIELD = new ParseField("disable_coord").withAllDeprecated("Use [bool] query instead");
    private static final ParseField EXECUTION_FIELD = new ParseField("execution").withAllDeprecated("execution is deprecated and has no effect");

    @Override
    public String[] names() {
        return new String[]{TermsQueryBuilder.NAME, "in"};
    }

    @Override
    public QueryBuilder fromXContent(QueryParseContext parseContext) throws IOException {
        XContentParser parser = parseContext.parser();

        String fieldName = null;
        List<Object> values = null;
        String minShouldMatch = null;
        boolean disableCoord = TermsQueryBuilder.DEFAULT_DISABLE_COORD;
        TermsLookup termsLookup = null;

        String queryName = null;
        float boost = AbstractQueryBuilder.DEFAULT_BOOST;

        XContentParser.Token token;
        String currentFieldName = null;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (parseContext.isDeprecatedSetting(currentFieldName)) {
                // skip
            } else if (token == XContentParser.Token.START_ARRAY) {
                if  (fieldName != null) {
                    throw new ParsingException(parser.getTokenLocation(), "[terms] query does not support multiple fields");
                }
                fieldName = currentFieldName;
                values = parseValues(parser);
            } else if (token == XContentParser.Token.START_OBJECT) {
                fieldName = currentFieldName;
                termsLookup = TermsLookup.parseTermsLookup(parser);
            } else if (token.isValue()) {
                if (parseContext.parseFieldMatcher().match(currentFieldName, EXECUTION_FIELD)) {
                    // ignore
                } else if (parseContext.parseFieldMatcher().match(currentFieldName, MIN_SHOULD_MATCH_FIELD)) {
                    if (minShouldMatch != null) {
                        throw new IllegalArgumentException("[" + currentFieldName + "] is not allowed in a filter context for the [" + TermsQueryBuilder.NAME + "] query");
                    }
                    minShouldMatch = parser.textOrNull();
                } else if ("boost".equals(currentFieldName)) {
                    boost = parser.floatValue();
                } else if (parseContext.parseFieldMatcher().match(currentFieldName, DISABLE_COORD_FIELD)) {
                    disableCoord = parser.booleanValue();
                } else if ("_name".equals(currentFieldName)) {
                    queryName = parser.text();
                } else {
                    throw new ParsingException(parser.getTokenLocation(), "[terms] query does not support [" + currentFieldName + "]");
                }
            }
        }

        if (fieldName == null) {
            throw new ParsingException(parser.getTokenLocation(), "terms query requires a field name, followed by array of terms or a document lookup specification");
        }
        return new TermsQueryBuilder(fieldName, values, minShouldMatch, disableCoord, termsLookup)
                .boost(boost)
                .queryName(queryName);
    }

    private static List<Object> parseValues(XContentParser parser) throws IOException {
        List<Object> values = new ArrayList<>();
        while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
            Object value = parser.objectBytes();
            if (value == null) {
                throw new ParsingException(parser.getTokenLocation(), "No value specified for terms query");
            }
            values.add(value);
        }
        return values;
    }

    @Override
    public TermsQueryBuilder getBuilderPrototype() {
        return TermsQueryBuilder.PROTOTYPE;
    }
}
