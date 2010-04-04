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

import org.elasticsearch.index.query.QueryBuilderException;
import org.elasticsearch.util.json.JsonBuilder;

import java.io.IOException;

/**
 * @author kimchy (shay.banon)
 */
public class FuzzyLikeThisFieldJsonQueryBuilder extends BaseJsonQueryBuilder {

    private final String name;

    private Float boost;

    private String likeText = null;
    private Float minSimilarity;
    private Integer prefixLength;
    private Integer maxQueryTerms;
    private Boolean ignoreTF;

    /**
     * A fuzzy more like this query on the provided field.
     *
     * @param name the name of the field
     */
    public FuzzyLikeThisFieldJsonQueryBuilder(String name) {
        this.name = name;
    }

    /**
     * The text to use in order to find documents that are "like" this.
     */
    public FuzzyLikeThisFieldJsonQueryBuilder likeText(String likeText) {
        this.likeText = likeText;
        return this;
    }

    public FuzzyLikeThisFieldJsonQueryBuilder minSimilarity(float minSimilarity) {
        this.minSimilarity = minSimilarity;
        return this;
    }

    public FuzzyLikeThisFieldJsonQueryBuilder prefixLength(int prefixLength) {
        this.prefixLength = prefixLength;
        return this;
    }

    public FuzzyLikeThisFieldJsonQueryBuilder maxQueryTerms(int maxQueryTerms) {
        this.maxQueryTerms = maxQueryTerms;
        return this;
    }

    public FuzzyLikeThisFieldJsonQueryBuilder ignoreTF(boolean ignoreTF) {
        this.ignoreTF = ignoreTF;
        return this;
    }

    public FuzzyLikeThisFieldJsonQueryBuilder boost(float boost) {
        this.boost = boost;
        return this;
    }

    @Override protected void doJson(JsonBuilder builder, Params params) throws IOException {
        builder.startObject(FuzzyLikeThisFieldJsonQueryParser.NAME);
        builder.startObject(name);
        if (likeText == null) {
            throw new QueryBuilderException("fuzzyLikeThis requires 'likeText' to be provided");
        }
        builder.field("like_text", likeText);
        if (maxQueryTerms != null) {
            builder.field("max_query_terms", maxQueryTerms);
        }
        if (minSimilarity != null) {
            builder.field("min_similarity", minSimilarity);
        }
        if (prefixLength != null) {
            builder.field("prefix_length", prefixLength);
        }
        if (ignoreTF != null) {
            builder.field("ignore_tf", ignoreTF);
        }
        if (boost != null) {
            builder.field("boost", boost);
        }
        builder.endObject();
        builder.endObject();
    }
}