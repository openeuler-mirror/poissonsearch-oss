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

package org.elasticsearch.index.mapper;

  import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermStates;
import org.apache.lucene.search.AutomatonQuery;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.lucene.search.AutomatonQueries;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.index.fielddata.IndexFieldData;
import org.elasticsearch.index.fielddata.plain.ConstantIndexFieldData;
import org.elasticsearch.index.query.QueryShardContext;
import org.elasticsearch.index.query.support.QueryParsers;
import org.elasticsearch.search.aggregations.support.CoreValuesSourceType;
import org.elasticsearch.search.lookup.SearchLookup;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.elasticsearch.search.SearchService.ALLOW_EXPENSIVE_QUERIES;

public class TypeFieldMapper extends MetadataFieldMapper {

    public static final String NAME = "_type";

    public static final String CONTENT_TYPE = "_type";

    public static final TypeParser PARSER = new FixedTypeParser(c -> new TypeFieldMapper());

    public static class Defaults {

        public static final FieldType NESTED_FIELD_TYPE = new FieldType();

        static {
            NESTED_FIELD_TYPE.setIndexOptions(IndexOptions.DOCS);
            NESTED_FIELD_TYPE.setTokenized(false);
            NESTED_FIELD_TYPE.setStored(false);
            NESTED_FIELD_TYPE.setOmitNorms(true);
            NESTED_FIELD_TYPE.freeze();
        }
    }

    public static final class TypeFieldType extends StringFieldType {

        public static final TypeFieldType INSTANCE = new TypeFieldType();

        private TypeFieldType() {
            super(NAME, true, false, TextSearchInfo.SIMPLE_MATCH_ONLY, Collections.emptyMap());
        }

        @Override
        public String typeName() {
            return CONTENT_TYPE;
        }

        @Override
        public IndexFieldData.Builder fielddataBuilder(String fullyQualifiedIndexName, Supplier<SearchLookup> searchLookup) {
            Function<MapperService, String> typeFunction = mapperService -> mapperService.documentMapper().type();
            return new ConstantIndexFieldData.Builder(typeFunction, name(), CoreValuesSourceType.BYTES);
        }

        @Override
        public boolean isSearchable() {
            return true;
        }

        @Override
        public Query existsQuery(QueryShardContext context) {
            return new MatchAllDocsQuery();
        }

        @Override
        public Query termQuery(Object value, QueryShardContext context) {
            return termsQuery(Arrays.asList(value), context);
        }

        @Override
        public Query termsQuery(List<?> values, QueryShardContext context) {
            DocumentMapper mapper = context.getMapperService().documentMapper();
            if (mapper == null) {
                return new MatchNoDocsQuery("No types");
            }
            BytesRef indexType = indexedValueForSearch(mapper.type());
            if (values.stream()
                    .map(this::indexedValueForSearch)
                    .anyMatch(indexType::equals)) {
                if (context.getMapperService().hasNested()) {
                    // type filters are expected not to match nested docs
                    return Queries.newNonNestedFilter(context.indexVersionCreated());
                } else {
                    return new MatchAllDocsQuery();
                }
            } else {
                return new MatchNoDocsQuery("Type list does not contain the index type");
            }
        }

        @Override
        public Query rangeQuery(Object lowerTerm, Object upperTerm, boolean includeLower, boolean includeUpper, QueryShardContext context) {
            Query result = new MatchAllDocsQuery();
            String type = context.getMapperService().documentMapper().type();
            if (type != null) {
                BytesRef typeBytes = new BytesRef(type);
                if (lowerTerm != null) {
                    int comp = indexedValueForSearch(lowerTerm).compareTo(typeBytes);
                    if (comp > 0 || (comp == 0 && includeLower == false)) {
                        result = new MatchNoDocsQuery("[_type] was lexicographically smaller than lower bound of range");
                    }
                }
                if (upperTerm != null) {
                    int comp = indexedValueForSearch(upperTerm).compareTo(typeBytes);
                    if (comp < 0 || (comp == 0 && includeUpper == false)) {
                        result = new MatchNoDocsQuery("[_type] was lexicographically greater than upper bound of range");
                    }
                }
            }
            return result;
        }

        @Override
        public Query wildcardQuery(String value, MultiTermQuery.RewriteMethod method, boolean caseInsensitive, QueryShardContext context) {
            Query termQuery = termQuery(value, context);
            if (termQuery instanceof MatchNoDocsQuery || termQuery instanceof MatchAllDocsQuery) {
                return termQuery;
            }

            if (context.allowExpensiveQueries() == false) {
                throw new ElasticsearchException("[wildcard] queries cannot be executed when '" +
                        ALLOW_EXPENSIVE_QUERIES.getKey() + "' is set to false.");
            }
            Term term = MappedFieldType.extractTerm(termQuery);

            if (caseInsensitive) {
                AutomatonQuery query = AutomatonQueries.caseInsensitiveWildcardQuery(term);
                QueryParsers.setRewriteMethod(query, method);
                return query;            
            }
            
            WildcardQuery query = new WildcardQuery(term);
            QueryParsers.setRewriteMethod(query, method);
            return query;
        }
    }

    /**
     * Specialization for a disjunction over many _type
     */
    public static class TypesQuery extends Query {
        // Same threshold as TermInSetQuery
        private static final int BOOLEAN_REWRITE_TERM_COUNT_THRESHOLD = 16;

        private final BytesRef[] types;

        public TypesQuery(BytesRef... types) {
            if (types == null) {
                throw new NullPointerException("types cannot be null.");
            }
            if (types.length == 0) {
                throw new IllegalArgumentException("types must contains at least one value.");
            }
            this.types = types;
        }

        public BytesRef[] getTerms() {
            return types;
        }

        @Override
        public Query rewrite(IndexReader reader) throws IOException {
            final int threshold = Math.min(BOOLEAN_REWRITE_TERM_COUNT_THRESHOLD, BooleanQuery.getMaxClauseCount());
            if (types.length <= threshold) {
                Set<BytesRef> uniqueTypes = new HashSet<>();
                BooleanQuery.Builder bq = new BooleanQuery.Builder();
                int totalDocFreq = 0;
                for (BytesRef type : types) {
                    if (uniqueTypes.add(type)) {
                        Term term = new Term(CONTENT_TYPE, type);
                        TermStates context = TermStates.build(reader.getContext(), term, true);
                        if (context.docFreq() == 0) {
                            // this _type is not present in the reader
                            continue;
                        }
                        totalDocFreq += context.docFreq();
                        // strict equality should be enough ?
                        if (totalDocFreq >= reader.maxDoc()) {
                            assert totalDocFreq == reader.maxDoc();
                            // Matches all docs since _type is a single value field
                            // Using a match_all query will help Lucene perform some optimizations
                            // For instance, match_all queries as filter clauses are automatically removed
                            return new MatchAllDocsQuery();
                        }
                        bq.add(new TermQuery(term, context), BooleanClause.Occur.SHOULD);
                    }
                }
                return new ConstantScoreQuery(bq.build());
            }
            return new TermInSetQuery(CONTENT_TYPE, types);
        }

        @Override
        public boolean equals(Object obj) {
            if (sameClassAs(obj) == false) {
                return false;
            }
            TypesQuery that = (TypesQuery) obj;
            return Arrays.equals(types, that.types);
        }

        @Override
        public int hashCode() {
            return 31 * classHash() + Arrays.hashCode(types);
        }

        @Override
        public String toString(String field) {
            StringBuilder builder = new StringBuilder();
            for (BytesRef type : types) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append(new Term(CONTENT_TYPE, type).toString());
            }
            return builder.toString();
        }
    }

    private TypeFieldMapper() {
        super(new TypeFieldType());
    }

    @Override
    public void preParse(ParseContext context) throws IOException {
        if (fieldType.indexOptions() == IndexOptions.NONE && !fieldType.stored()) {
            return;
        }
        context.doc().add(new Field(fieldType().name(), context.sourceToParse().type(), fieldType));
        if (fieldType().hasDocValues()) {
            context.doc().add(new SortedSetDocValuesField(fieldType().name(), new BytesRef(MapperService.SINGLE_MAPPING_NAME)));
        }
    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

}
