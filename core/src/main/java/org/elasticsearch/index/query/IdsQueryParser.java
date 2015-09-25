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
import org.elasticsearch.common.xcontent.XContentParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parser for ids query
 */
public class IdsQueryParser implements QueryParser<IdsQueryBuilder> {

    @Override
    public String[] names() {
        return new String[]{IdsQueryBuilder.NAME};
    }

    /**
     * @return a QueryBuilder representation of the query passed in as XContent in the parse context
     */
    @Override
    public IdsQueryBuilder fromXContent(QueryParseContext parseContext) throws IOException {
        XContentParser parser = parseContext.parser();
        List<String> ids = new ArrayList<>();
        List<String> types = new ArrayList<>();
        float boost = AbstractQueryBuilder.DEFAULT_BOOST;
        String queryName = null;

        String currentFieldName = null;
        XContentParser.Token token;
        boolean idsProvided = false;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (token == XContentParser.Token.START_ARRAY) {
                if ("values".equals(currentFieldName)) {
                    idsProvided = true;
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        if ((token == XContentParser.Token.VALUE_STRING) ||
                                (token == XContentParser.Token.VALUE_NUMBER)) {
                            String id = parser.textOrNull();
                            if (id == null) {
                                throw new ParsingException(parser.getTokenLocation(), "No value specified for term filter");
                            }
                            ids.add(id);
                        } else {
                            throw new ParsingException(parser.getTokenLocation(), "Illegal value for id, expecting a string or number, got: "
                                    + token);
                        }
                    }
                } else if ("types".equals(currentFieldName) || "type".equals(currentFieldName)) {
                    while ((token = parser.nextToken()) != XContentParser.Token.END_ARRAY) {
                        String value = parser.textOrNull();
                        if (value == null) {
                            throw new ParsingException(parser.getTokenLocation(), "No type specified for term filter");
                        }
                        types.add(value);
                    }
                } else {
                    throw new ParsingException(parser.getTokenLocation(), "[ids] query does not support [" + currentFieldName + "]");
                }
            } else if (token.isValue()) {
                if ("type".equals(currentFieldName) || "_type".equals(currentFieldName)) {
                    types = Collections.singletonList(parser.text());
                } else if ("boost".equals(currentFieldName)) {
                    boost = parser.floatValue();
                } else if ("_name".equals(currentFieldName)) {
                    queryName = parser.text();
                } else {
                    throw new ParsingException(parser.getTokenLocation(), "[ids] query does not support [" + currentFieldName + "]");
                }
            }
        }
        if (!idsProvided) {
            throw new ParsingException(parser.getTokenLocation(), "[ids] query, no ids values provided");
        }

        IdsQueryBuilder query = new IdsQueryBuilder(types.toArray(new String[types.size()]));
        query.addIds(ids.toArray(new String[ids.size()]));
        query.boost(boost).queryName(queryName);
        return query;
    }

    @Override
    public IdsQueryBuilder getBuilderPrototype() {
        return IdsQueryBuilder.PROTOTYPE;
    }
}
