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


package org.elasticsearch.search.aggregations.bucket.significant.heuristics;


import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParseFieldMatcher;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.QueryShardException;

import java.io.IOException;

public class PercentageScore extends SignificanceHeuristic {

    public static final PercentageScore PROTOTYPE = new PercentageScore();

    protected static final ParseField NAMES_FIELD = new ParseField("percentage");

    private PercentageScore() {}

    @Override
    public String getWriteableName() {
        return NAMES_FIELD.getPreferredName();
    }

    @Override
    public SignificanceHeuristic readFrom(StreamInput in) throws IOException {
        return PROTOTYPE;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAMES_FIELD.getPreferredName()).endObject();
        return builder;
    }

    /**
     * Indicates the significance of a term in a sample by determining what percentage
     * of all occurrences of a term are found in the sample.
     */
    @Override
    public double getScore(long subsetFreq, long subsetSize, long supersetFreq, long supersetSize) {
        checkFrequencyValidity(subsetFreq, subsetSize, supersetFreq, supersetSize, "PercentageScore");
        if (supersetFreq == 0) {
            // avoid a divide by zero issue
            return 0;
        }
        return (double) subsetFreq / (double) supersetFreq;
   }

    public static class PercentageScoreParser implements SignificanceHeuristicParser {

        @Override
        public SignificanceHeuristic parse(XContentParser parser, ParseFieldMatcher parseFieldMatcher)
                throws IOException, QueryShardException {
            // move to the closing bracket
            if (!parser.nextToken().equals(XContentParser.Token.END_OBJECT)) {
                throw new ElasticsearchParseException("failed to parse [percentage] significance heuristic. expected an empty object, but got [{}] instead", parser.currentToken());
            }
            return PROTOTYPE;
        }

        @Override
        public String[] getNames() {
            return NAMES_FIELD.getAllNamesIncludedDeprecated();
        }
    }

    public static class PercentageScoreBuilder implements SignificanceHeuristicBuilder {

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject(NAMES_FIELD.getPreferredName()).endObject();
            return builder;
        }
    }
}

