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

package org.elasticsearch.index.query.json;

import com.google.inject.Inject;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.elasticsearch.index.AbstractIndexComponent;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.query.QueryParsingException;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.util.settings.Settings;

import java.io.IOException;

import static org.elasticsearch.index.query.support.QueryParsers.*;

/**
 * @author kimchy (shay.banon)
 */
public class PrefixJsonQueryParser extends AbstractIndexComponent implements JsonQueryParser {

    public static final String NAME = "prefix";

    @Inject public PrefixJsonQueryParser(Index index, @IndexSettings Settings settings) {
        super(index, settings);
    }

    @Override public String[] names() {
        return new String[]{NAME};
    }

    @Override public Query parse(JsonQueryParseContext parseContext) throws IOException, QueryParsingException {
        JsonParser jp = parseContext.jp();

        JsonToken token = jp.nextToken();
        assert token == JsonToken.FIELD_NAME;
        String fieldName = jp.getCurrentName();


        String value = null;
        float boost = 1.0f;
        token = jp.nextToken();
        if (token == JsonToken.START_OBJECT) {
            String currentFieldName = null;
            while ((token = jp.nextToken()) != JsonToken.END_OBJECT) {
                if (token == JsonToken.FIELD_NAME) {
                    currentFieldName = jp.getCurrentName();
                } else if (token == JsonToken.VALUE_STRING) {
                    if (NAME.equals(currentFieldName)) {
                        value = jp.getText();
                    } else if ("boost".equals(currentFieldName)) {
                        boost = Float.parseFloat(jp.getText());
                    }
                } else if (token == JsonToken.VALUE_NUMBER_INT) {
                    if ("boost".equals(currentFieldName)) {
                        boost = jp.getIntValue();
                    }
                } else if (token == JsonToken.VALUE_NUMBER_FLOAT) {
                    if ("boost".equals(currentFieldName)) {
                        boost = jp.getFloatValue();
                    }
                }
            }
        } else {
            value = jp.getText();
            jp.nextToken();
        }

        if (value == null) {
            throw new QueryParsingException(index, "No value specified for prefix query");
        }

        MapperService.SmartNameFieldMappers smartNameFieldMappers = parseContext.smartFieldMappers(fieldName);
        if (smartNameFieldMappers != null) {
            if (smartNameFieldMappers.hasMapper()) {
                fieldName = smartNameFieldMappers.mapper().names().indexName();
                value = smartNameFieldMappers.mapper().indexedValue(value);
            }
        }

        PrefixQuery query = new PrefixQuery(new Term(fieldName, value));
        query.setRewriteMethod(MultiTermQuery.CONSTANT_SCORE_AUTO_REWRITE_DEFAULT);
        query.setBoost(boost);
        return wrapSmartNameQuery(query, smartNameFieldMappers, parseContext.indexCache());
    }
}