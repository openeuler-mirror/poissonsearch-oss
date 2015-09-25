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

import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;

public class MatchNoneQueryParser implements QueryParser<MatchNoneQueryBuilder> {

    @Override
    public String[] names() {
        return new String[]{MatchNoneQueryBuilder.NAME, Strings.toCamelCase(MatchNoneQueryBuilder.NAME)};
    }

    @Override
    public MatchNoneQueryBuilder fromXContent(QueryParseContext parseContext) throws IOException {
        XContentParser parser = parseContext.parser();

        XContentParser.Token token = parser.nextToken();
        if (token != XContentParser.Token.END_OBJECT) {
            throw new ParsingException(parser.getTokenLocation(), "[match_none] query malformed");
        }

        return new MatchNoneQueryBuilder();
    }

    @Override
    public MatchNoneQueryBuilder getBuilderPrototype() {
        return MatchNoneQueryBuilder.PROTOTYPE;
    }
}
