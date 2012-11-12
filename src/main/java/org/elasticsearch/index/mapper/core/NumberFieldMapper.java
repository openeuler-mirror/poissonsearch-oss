/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.index.mapper.core;

import org.apache.lucene.analysis.NumericTokenStream;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.FieldInfo.IndexOptions;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.Query;
import org.apache.lucene.util.NumericUtils;
import org.elasticsearch.common.Explicit;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.cache.field.data.FieldDataCache;
import org.elasticsearch.index.field.data.FieldDataType;
import org.elasticsearch.index.mapper.*;
import org.elasticsearch.index.mapper.internal.AllFieldMapper;
import org.elasticsearch.index.query.QueryParseContext;

import java.io.IOException;
import java.io.Reader;

/**
 *
 */
public abstract class NumberFieldMapper<T extends Number> extends AbstractFieldMapper<T> implements AllFieldMapper.IncludeInAll {

    public static class Defaults extends AbstractFieldMapper.Defaults {
        public static final int PRECISION_STEP = NumericUtils.PRECISION_STEP_DEFAULT;

        public static final FieldType NUMBER_FIELD_TYPE = new FieldType(AbstractFieldMapper.Defaults.FIELD_TYPE);

        static {
            NUMBER_FIELD_TYPE.setTokenized(false);
            NUMBER_FIELD_TYPE.setOmitNorms(true);
            NUMBER_FIELD_TYPE.setIndexOptions(IndexOptions.DOCS_ONLY);
            NUMBER_FIELD_TYPE.setStoreTermVectors(false);
            NUMBER_FIELD_TYPE.freeze();
        }

        public static final String FUZZY_FACTOR = null;
        public static final Explicit<Boolean> IGNORE_MALFORMED = new Explicit<Boolean>(false, false);
    }

    public abstract static class Builder<T extends Builder, Y extends NumberFieldMapper> extends AbstractFieldMapper.Builder<T, Y> {

        protected int precisionStep = Defaults.PRECISION_STEP;

        protected String fuzzyFactor = Defaults.FUZZY_FACTOR;

        private Boolean ignoreMalformed;

        public Builder(String name, FieldType fieldType) {
            super(name, fieldType);
        }

        @Override
        public T store(boolean store) {
            return super.store(store);
        }

        @Override
        public T boost(float boost) {
            return super.boost(boost);
        }

        @Override
        public T indexName(String indexName) {
            return super.indexName(indexName);
        }

        @Override
        public T includeInAll(Boolean includeInAll) {
            return super.includeInAll(includeInAll);
        }

        public T precisionStep(int precisionStep) {
            this.precisionStep = precisionStep;
            return builder;
        }

        public T fuzzyFactor(String fuzzyFactor) {
            this.fuzzyFactor = fuzzyFactor;
            return builder;
        }

        public T ignoreMalformed(boolean ignoreMalformed) {
            this.ignoreMalformed = ignoreMalformed;
            return builder;
        }

        protected Explicit<Boolean> ignoreMalformed(BuilderContext context) {
            if (ignoreMalformed != null) {
                return new Explicit<Boolean>(ignoreMalformed, true);
            }
            if (context.indexSettings() != null) {
                return new Explicit<Boolean>(context.indexSettings().getAsBoolean("index.mapping.ignore_malformed", Defaults.IGNORE_MALFORMED.value()), false);
            }
            return Defaults.IGNORE_MALFORMED;
        }
    }

    protected int precisionStep;

    protected String fuzzyFactor;

    protected double dFuzzyFactor;

    protected Boolean includeInAll;

    protected Explicit<Boolean> ignoreMalformed;

    private ThreadLocal<NumericTokenStream> tokenStream = new ThreadLocal<NumericTokenStream>() {
        @Override
        protected NumericTokenStream initialValue() {
            return new NumericTokenStream(precisionStep);
        }
    };

    protected NumberFieldMapper(Names names, int precisionStep, @Nullable String fuzzyFactor,
                                float boost, FieldType fieldType,
                                Explicit<Boolean> ignoreMalformed, NamedAnalyzer indexAnalyzer, NamedAnalyzer searchAnalyzer) {
        // LUCENE 4 UPGRADE: Since we can't do anything before the super call, we have to push the boost check down to subclasses
        super(names, boost, fieldType, indexAnalyzer, searchAnalyzer);
        if (precisionStep <= 0 || precisionStep >= maxPrecisionStep()) {
            this.precisionStep = Integer.MAX_VALUE;
        } else {
            this.precisionStep = precisionStep;
        }
        this.fuzzyFactor = fuzzyFactor;
        this.dFuzzyFactor = parseFuzzyFactor(fuzzyFactor);
        this.ignoreMalformed = ignoreMalformed;
    }

    protected double parseFuzzyFactor(String fuzzyFactor) {
        if (fuzzyFactor == null) {
            return 1.0d;
        }
        return Double.parseDouble(fuzzyFactor);
    }

    @Override
    public void includeInAll(Boolean includeInAll) {
        if (includeInAll != null) {
            this.includeInAll = includeInAll;
        }
    }

    @Override
    public void includeInAllIfNotSet(Boolean includeInAll) {
        if (includeInAll != null && this.includeInAll == null) {
            this.includeInAll = includeInAll;
        }
    }

    protected abstract int maxPrecisionStep();

    public int precisionStep() {
        return this.precisionStep;
    }

    @Override
    protected Field parseCreateField(ParseContext context) throws IOException {
        RuntimeException e;
        try {
            return innerParseCreateField(context);
        } catch (IllegalArgumentException e1) {
            e = e1;
        } catch (MapperParsingException e2) {
            e = e2;
        }

        if (ignoreMalformed.value()) {
            return null;
        } else {
            throw e;
        }
    }

    protected abstract Field innerParseCreateField(ParseContext context) throws IOException;

    /**
     * Use the field query created here when matching on numbers.
     */
    @Override
    public boolean useFieldQueryWithQueryString() {
        return true;
    }

    /**
     * Numeric field level query are basically range queries with same value and included. That's the recommended
     * way to execute it.
     */
    @Override
    public Query fieldQuery(String value, @Nullable QueryParseContext context) {
        return rangeQuery(value, value, true, true, context);
    }

    @Override
    public abstract Query fuzzyQuery(String value, String minSim, int prefixLength, int maxExpansions, boolean transpositions);

    @Override
    public abstract Query fuzzyQuery(String value, double minSim, int prefixLength, int maxExpansions, boolean transpositions);

    /**
     * Numeric field level filter are basically range queries with same value and included. That's the recommended
     * way to execute it.
     */
    @Override
    public Filter fieldFilter(String value, @Nullable QueryParseContext context) {
        return rangeFilter(value, value, true, true, context);
    }

    @Override
    public abstract Query rangeQuery(String lowerTerm, String upperTerm, boolean includeLower, boolean includeUpper, @Nullable QueryParseContext context);

    @Override
    public abstract Filter rangeFilter(String lowerTerm, String upperTerm, boolean includeLower, boolean includeUpper, @Nullable QueryParseContext context);

    /**
     * A range filter based on the field data cache.
     */
    public abstract Filter rangeFilter(FieldDataCache fieldDataCache, String lowerTerm, String upperTerm, boolean includeLower, boolean includeUpper, @Nullable QueryParseContext context);

    /**
     * Override the default behavior (to return the string, and return the actual Number instance).
     */
    @Override
    public Object valueForSearch(Field field) {
        return value(field);
    }

    @Override
    public String valueAsString(Field field) {
        Number num = value(field);
        return num == null ? null : num.toString();
    }

    @Override
    public void merge(Mapper mergeWith, MergeContext mergeContext) throws MergeMappingException {
        super.merge(mergeWith, mergeContext);
        if (!this.getClass().equals(mergeWith.getClass())) {
            return;
        }
        if (!mergeContext.mergeFlags().simulate()) {
            NumberFieldMapper nfmMergeWith = (NumberFieldMapper) mergeWith;
            this.precisionStep = nfmMergeWith.precisionStep;
            this.includeInAll = nfmMergeWith.includeInAll;
            this.fuzzyFactor = nfmMergeWith.fuzzyFactor;
            this.dFuzzyFactor = parseFuzzyFactor(nfmMergeWith.fuzzyFactor);
            if (nfmMergeWith.ignoreMalformed.explicit()) {
                this.ignoreMalformed = nfmMergeWith.ignoreMalformed;
            }
        }
    }

    @Override
    public void close() {
        tokenStream.remove();
    }

    @Override
    public abstract FieldDataType fieldDataType();

    protected NumericTokenStream popCachedStream() {
        return tokenStream.get();
    }

    // used to we can use a numeric field in a document that is then parsed twice!
    public abstract static class CustomNumericField extends Field {

        protected final NumberFieldMapper mapper;

        public CustomNumericField(NumberFieldMapper mapper, byte[] value, FieldType fieldType) {
            super(mapper.names().indexName(), value, fieldType);
            this.mapper = mapper;
        }

        @Override
        public String stringValue() {
            return null;
        }

        @Override
        public Reader readerValue() {
            return null;
        }

        public abstract String numericAsString();
    }

    @Override
    protected void doXContentBody(XContentBuilder builder) throws IOException {
        super.doXContentBody(builder);
        if (ignoreMalformed.explicit()) {
            builder.field("ignore_malformed", ignoreMalformed.value());
        }
    }
}
