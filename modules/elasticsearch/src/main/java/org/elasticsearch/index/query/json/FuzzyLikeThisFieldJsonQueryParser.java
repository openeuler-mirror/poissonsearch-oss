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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.search.FuzzyLikeThisQuery;
import org.apache.lucene.search.Query;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonToken;
import org.elasticsearch.index.AbstractIndexComponent;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.query.QueryParsingException;
import org.elasticsearch.index.settings.IndexSettings;
import org.elasticsearch.util.Booleans;
import org.elasticsearch.util.settings.Settings;

import java.io.IOException;

import static org.elasticsearch.index.query.support.QueryParsers.*;

/**
 * <pre>
 * {
 *  fuzzy_like_This_field : {
 *      field1 : {
 *          maxNumTerms : 12,
 *          boost : 1.1,
 *          likeText : "..."
 *      }
 * }
 * </pre>
 *
 * @author kimchy (shay.banon)
 */
public class FuzzyLikeThisFieldJsonQueryParser extends AbstractIndexComponent implements JsonQueryParser {

    public static final String NAME = "flt_field";

    public FuzzyLikeThisFieldJsonQueryParser(Index index, @IndexSettings Settings indexSettings) {
        super(index, indexSettings);
    }

    @Override public String[] names() {
        return new String[]{NAME, "fuzzy_like_this_field"};
    }

    @Override public Query parse(JsonQueryParseContext parseContext) throws IOException, QueryParsingException {
        JsonParser jp = parseContext.jp();

        int maxNumTerms = 25;
        float boost = 1.0f;
        String likeText = null;
        float minSimilarity = 0.5f;
        int prefixLength = 0;
        boolean ignoreTF = false;

        JsonToken token = jp.nextToken();
        assert token == JsonToken.FIELD_NAME;
        String fieldName = jp.getCurrentName();

        // now, we move after the field name, which starts the object
        token = jp.nextToken();
        assert token == JsonToken.START_OBJECT;


        String currentFieldName = null;
        while ((token = jp.nextToken()) != JsonToken.END_OBJECT) {
            if (token == JsonToken.FIELD_NAME) {
                currentFieldName = jp.getCurrentName();
            } else if (token == JsonToken.VALUE_STRING) {
                if ("like_text".equals(currentFieldName)) {
                    likeText = jp.getText();
                } else if ("max_query_terms".equals(currentFieldName)) {
                    maxNumTerms = Integer.parseInt(jp.getText());
                } else if ("boost".equals(currentFieldName)) {
                    boost = Float.parseFloat(jp.getText());
                } else if ("ignore_tf".equals(currentFieldName)) {
                    ignoreTF = Booleans.parseBoolean(jp.getText(), false);
                }
            } else if (token == JsonToken.VALUE_NUMBER_INT) {
                if ("max_query_terms".equals(currentFieldName)) {
                    maxNumTerms = jp.getIntValue();
                } else if ("boost".equals(currentFieldName)) {
                    boost = jp.getIntValue();
                } else if ("ignore_tf".equals(currentFieldName)) {
                    ignoreTF = jp.getIntValue() != 0;
                }
            } else if (token == JsonToken.VALUE_TRUE) {
                if ("ignore_tf".equals(currentFieldName)) {
                    ignoreTF = true;
                }
            } else if (token == JsonToken.VALUE_NUMBER_FLOAT) {
                if ("boost".equals(currentFieldName)) {
                    boost = jp.getFloatValue();
                }
            }
        }

        if (likeText == null) {
            throw new QueryParsingException(index, "fuzzy_like_This_field requires 'likeText' to be specified");
        }

        Analyzer analyzer = null;
        MapperService.SmartNameFieldMappers smartNameFieldMappers = parseContext.smartFieldMappers(fieldName);
        if (smartNameFieldMappers != null) {
            if (smartNameFieldMappers.hasMapper()) {
                fieldName = smartNameFieldMappers.mapper().names().indexName();
                analyzer = smartNameFieldMappers.mapper().searchAnalyzer();
            }
        }
        if (analyzer == null) {
            analyzer = parseContext.mapperService().searchAnalyzer();
        }

        FuzzyLikeThisQuery query = new FuzzyLikeThisQuery(maxNumTerms, analyzer);
        query.addTerms(likeText, fieldName, minSimilarity, prefixLength);
        query.setBoost(boost);
        query.setIgnoreTF(ignoreTF);

        // move to the next end object, to close the field name
        token = jp.nextToken();
        assert token == JsonToken.END_OBJECT;

        return wrapSmartNameQuery(query, smartNameFieldMappers, parseContext.indexCache());
    }
}