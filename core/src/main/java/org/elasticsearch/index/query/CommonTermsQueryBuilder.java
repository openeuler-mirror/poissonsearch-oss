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

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.queries.ExtendedCommonTermsQuery;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.similarities.Similarity;
import org.apache.lucene.util.BytesRefBuilder;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.MappedFieldType;

import java.io.IOException;
import java.util.Objects;

/**
 * CommonTermsQuery query is a query that executes high-frequency terms in a
 * optional sub-query to prevent slow queries due to "common" terms like
 * stopwords. This query basically builds 2 queries off the
 * {@link org.apache.lucene.queries.CommonTermsQuery#add(Term) added} terms 
 * where low-frequency terms are added to a required boolean clause
 * and high-frequency terms are added to an optional boolean clause. The
 * optional clause is only executed if the required "low-frequency' clause
 * matches. Scores produced by this query will be slightly different to plain
 * {@link BooleanQuery} scorer mainly due to differences in the
 * {@link Similarity#coord(int,int) number of leave queries} in the required
 * boolean clause. In the most cases high-frequency terms are unlikely to
 * significantly contribute to the document score unless at least one of the
 * low-frequency terms are matched such that this query can improve query
 * execution times significantly if applicable.
 * <p>
 */
public class CommonTermsQueryBuilder extends AbstractQueryBuilder<CommonTermsQueryBuilder> implements BoostableQueryBuilder<CommonTermsQueryBuilder> {

    public static final String NAME = "common";

    public static final float DEFAULT_CUTOFF_FREQ = 0.01f;

    public static final Operator DEFAULT_HIGH_FREQ_OCCUR = Operator.OR;

    public static final Operator DEFAULT_LOW_FREQ_OCCUR = Operator.OR;

    public static final boolean DEFAULT_DISABLE_COORD = true;

    private final String fieldName;

    private final Object text;

    private Operator highFreqOperator = DEFAULT_HIGH_FREQ_OCCUR;

    private Operator lowFreqOperator = DEFAULT_LOW_FREQ_OCCUR;

    private String analyzer = null;

    private float boost = 1.0f;

    private String lowFreqMinimumShouldMatch = null;

    private String highFreqMinimumShouldMatch = null;

    private boolean disableCoord = DEFAULT_DISABLE_COORD;

    private float cutoffFrequency = DEFAULT_CUTOFF_FREQ;

    private String queryName;

    static final CommonTermsQueryBuilder PROTOTYPE = new CommonTermsQueryBuilder(null, null);

    /**
     * Constructs a new common terms query.
     */
    public CommonTermsQueryBuilder(String fieldName, Object text) {
        this.fieldName = fieldName;
        this.text = text;
    }

    public String fieldName() {
        return this.fieldName;
    }

    public Object text() {
        return this.text;
    }

    /**
     * Sets the operator to use for terms with a high document frequency
     * (greater than or equal to {@link #cutoffFrequency(float)}. Defaults to
     * <tt>AND</tt>.
     */
    public CommonTermsQueryBuilder highFreqOperator(Operator operator) {
        this.highFreqOperator = (operator == null) ? DEFAULT_HIGH_FREQ_OCCUR : operator;
        return this;
    }

    public Operator highFreqOperator() {
        return highFreqOperator;
    }

    /**
     * Sets the operator to use for terms with a low document frequency (less
     * than {@link #cutoffFrequency(float)}. Defaults to <tt>AND</tt>.
     */
    public CommonTermsQueryBuilder lowFreqOperator(Operator operator) {
        this.lowFreqOperator = (operator == null) ? DEFAULT_LOW_FREQ_OCCUR : operator;
        return this;
    }

    public Operator lowFreqOperator() {
        return lowFreqOperator;
    }

    /**
     * Explicitly set the analyzer to use. Defaults to use explicit mapping
     * config for the field, or, if not set, the default search analyzer.
     */
    public CommonTermsQueryBuilder analyzer(String analyzer) {
        this.analyzer = analyzer;
        return this;
    }

    public String analyzer() {
        return this.analyzer;
    }

    /**
     * Set the boost to apply to the query.
     */
    @Override
    public CommonTermsQueryBuilder boost(float boost) {
        this.boost = boost;
        return this;
    }

    public float boost() {
        return boost;
    }

    /**
     * Sets the cutoff document frequency for high / low frequent terms. A value
     * in [0..1] (or absolute number >=1) representing the maximum threshold of
     * a terms document frequency to be considered a low frequency term.
     * Defaults to
     * <tt>{@value #DEFAULT_CUTOFF_FREQ}</tt>
     */
    public CommonTermsQueryBuilder cutoffFrequency(float cutoffFrequency) {
        this.cutoffFrequency = cutoffFrequency;
        return this;
    }
    
    public float cutoffFrequency() {
        return this.cutoffFrequency;
    }

    /**
     * Sets the minimum number of high frequent query terms that need to match in order to
     * produce a hit when there are no low frequen terms.
     */
    public CommonTermsQueryBuilder highFreqMinimumShouldMatch(String highFreqMinimumShouldMatch) {
        this.highFreqMinimumShouldMatch = highFreqMinimumShouldMatch;
        return this;
    }

    public String highFreqMinimumShouldMatch() {
        return this.highFreqMinimumShouldMatch;
    }

    /**
     * Sets the minimum number of low frequent query terms that need to match in order to
     * produce a hit.
     */
    public CommonTermsQueryBuilder lowFreqMinimumShouldMatch(String lowFreqMinimumShouldMatch) {
        this.lowFreqMinimumShouldMatch = lowFreqMinimumShouldMatch;
        return this;
    }
    
    public String lowFreqMinimumShouldMatch() {
        return this.lowFreqMinimumShouldMatch;
    }

    public CommonTermsQueryBuilder disableCoord(boolean disableCoord) {
        this.disableCoord = disableCoord;
        return this;
    }

    public boolean disableCoord() {
        return this.disableCoord;
    }

    /**
     * Sets the query name for the filter that can be used when searching for matched_filters per hit.
     */
    public CommonTermsQueryBuilder queryName(String queryName) {
        this.queryName = queryName;
        return this;
    }

    public String queryName() {
        return this.queryName;
    }

    @Override
    public void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.startObject(fieldName);

        builder.field("query", text);
        builder.field("disable_coord", disableCoord);
        builder.field("high_freq_operator", highFreqOperator.toString());
        builder.field("low_freq_operator", lowFreqOperator.toString());
        if (analyzer != null) {
            builder.field("analyzer", analyzer);
        }
        builder.field("boost", boost);
        builder.field("cutoff_frequency", cutoffFrequency);
        if (lowFreqMinimumShouldMatch != null || highFreqMinimumShouldMatch != null) {
            builder.startObject("minimum_should_match");
            if (lowFreqMinimumShouldMatch != null) {
                builder.field("low_freq", lowFreqMinimumShouldMatch);
            }
            if (highFreqMinimumShouldMatch != null) {
                builder.field("high_freq", highFreqMinimumShouldMatch);
            }
            builder.endObject();
        }
        if (queryName != null) {
            builder.field("_name", queryName);
        }
        builder.endObject();
        builder.endObject();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Query toQuery(QueryParseContext parseContext) throws QueryParsingException, IOException {
        String field;
        MappedFieldType fieldType = parseContext.fieldMapper(fieldName);
        if (fieldType != null) {
            field = fieldType.names().indexName();
        } else {
            field = fieldName;
        }

        Analyzer analyzerObj;
        if (analyzer == null) {
            if (fieldType != null) {
                analyzerObj = parseContext.getSearchAnalyzer(fieldType);
            } else {
                analyzerObj = parseContext.mapperService().searchAnalyzer();
            }
        } else {
            analyzerObj = parseContext.mapperService().analysisService().analyzer(analyzer);
            if (analyzerObj == null) {
                throw new IllegalArgumentException("no analyzer found for [" + analyzer + "]");
            }
        }
        
        Occur highFreqOccur = highFreqOperator.toBooleanClauseOccur();
        Occur lowFreqOccur = lowFreqOperator.toBooleanClauseOccur();

        ExtendedCommonTermsQuery commonsQuery = new ExtendedCommonTermsQuery(highFreqOccur, lowFreqOccur, cutoffFrequency, disableCoord, fieldType);
        commonsQuery.setBoost(boost);
        Query query = parseQueryString(commonsQuery, text, field, analyzerObj, lowFreqMinimumShouldMatch, highFreqMinimumShouldMatch);
        if (queryName != null) {
            parseContext.addNamedQuery(queryName, query);
        }
        return query;
    }
    
    static Query parseQueryString(ExtendedCommonTermsQuery query, Object queryString, String field, Analyzer analyzer, 
                                         String lowFreqMinimumShouldMatch, String highFreqMinimumShouldMatch) throws IOException {
        // Logic similar to QueryParser#getFieldQuery
        int count = 0;
        try (TokenStream source = analyzer.tokenStream(field, queryString.toString())) {
            source.reset();
            CharTermAttribute termAtt = source.addAttribute(CharTermAttribute.class);
            BytesRefBuilder builder = new BytesRefBuilder();
            while (source.incrementToken()) {
                // UTF-8
                builder.copyChars(termAtt);
                query.add(new Term(field, builder.toBytesRef()));
                count++;
            }
        }

        if (count == 0) {
            return null;
        }
        query.setLowFreqMinimumNumberShouldMatch(lowFreqMinimumShouldMatch);
        query.setHighFreqMinimumNumberShouldMatch(highFreqMinimumShouldMatch);
        return query;
    }

    @Override
    public QueryValidationException validate() {
        QueryValidationException validationException = null;
        if (Strings.isEmpty(this.fieldName)) {
            validationException = QueryValidationException.addValidationError("field name cannot be null or empty.", validationException);
        }
        if (this.text == null) {
            validationException = QueryValidationException.addValidationError("query text cannot be null", validationException);
        }
        return validationException;
    }

    @Override
    public CommonTermsQueryBuilder readFrom(StreamInput in) throws IOException {
        CommonTermsQueryBuilder commonTermsQueryBuilder = new CommonTermsQueryBuilder(in.readString(), in.readGenericValue());
        commonTermsQueryBuilder.highFreqOperator = Operator.readOperatorFrom(in);
        commonTermsQueryBuilder.lowFreqOperator = Operator.readOperatorFrom(in);
        commonTermsQueryBuilder.analyzer = in.readOptionalString();
        commonTermsQueryBuilder.boost = in.readFloat();
        commonTermsQueryBuilder.lowFreqMinimumShouldMatch = in.readOptionalString();
        commonTermsQueryBuilder.highFreqMinimumShouldMatch = in.readOptionalString();
        commonTermsQueryBuilder.disableCoord = in.readBoolean();
        commonTermsQueryBuilder.cutoffFrequency = in.readFloat();
        commonTermsQueryBuilder.queryName = in.readOptionalString();
        return commonTermsQueryBuilder;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(this.fieldName);
        out.writeGenericValue(this.text);
        highFreqOperator.writeTo(out);
        lowFreqOperator.writeTo(out);
        out.writeOptionalString(analyzer);
        out.writeFloat(boost);
        out.writeOptionalString(lowFreqMinimumShouldMatch);
        out.writeOptionalString(highFreqMinimumShouldMatch);
        out.writeBoolean(disableCoord);
        out.writeFloat(cutoffFrequency);
        out.writeOptionalString(queryName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldName, text, highFreqOperator, lowFreqOperator, analyzer, boost, 
                lowFreqMinimumShouldMatch, highFreqMinimumShouldMatch, disableCoord, cutoffFrequency, queryName);
    }

    @Override
    public boolean doEquals(CommonTermsQueryBuilder other) {
        return Objects.equals(fieldName, other.fieldName) &&
                Objects.equals(text, other.text) &&
                Objects.equals(highFreqOperator, other.highFreqOperator) &&
                Objects.equals(lowFreqOperator, other.lowFreqOperator) &&
                Objects.equals(analyzer, other.analyzer) &&
                Objects.equals(boost, other.boost) &&
                Objects.equals(lowFreqMinimumShouldMatch, other.lowFreqMinimumShouldMatch) &&
                Objects.equals(highFreqMinimumShouldMatch, other.highFreqMinimumShouldMatch) &&
                Objects.equals(disableCoord, other.disableCoord) &&
                Objects.equals(cutoffFrequency, other.cutoffFrequency) &&
                Objects.equals(queryName, other.queryName);
    }
}
