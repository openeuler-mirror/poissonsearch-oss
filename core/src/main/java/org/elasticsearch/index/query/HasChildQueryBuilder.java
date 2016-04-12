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

import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiDocValues;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchNoDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.join.JoinUtil;
import org.apache.lucene.search.join.ScoreMode;
import org.apache.lucene.search.similarities.Similarity;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.ParsingException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.fielddata.IndexParentChildFieldData;
import org.elasticsearch.index.fielddata.plain.ParentChildIndexFieldData;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.internal.ParentFieldMapper;
import org.elasticsearch.index.query.support.InnerHitBuilder;

import java.io.IOException;
import java.util.Locale;
import java.util.Objects;

/**
 * A query builder for <tt>has_child</tt> queries.
 */
public class HasChildQueryBuilder extends AbstractQueryBuilder<HasChildQueryBuilder> {

    /**
     * The queries name
     */
    public static final String NAME = "has_child";
    public static final ParseField QUERY_NAME_FIELD = new ParseField(NAME);

    /**
     * The default maximum number of children that are required to match for the parent to be considered a match.
     */
    public static final int DEFAULT_MAX_CHILDREN = Integer.MAX_VALUE;
    /**
     * The default minimum number of children that are required to match for the parent to be considered a match.
     */
    public static final int DEFAULT_MIN_CHILDREN = 0;
    /*
     * The default score mode that is used to combine score coming from multiple parent documents.
     */
    public static final ScoreMode DEFAULT_SCORE_MODE = ScoreMode.None;

    private static final ParseField QUERY_FIELD = new ParseField("query", "filter");
    private static final ParseField TYPE_FIELD = new ParseField("type", "child_type");
    private static final ParseField MAX_CHILDREN_FIELD = new ParseField("max_children");
    private static final ParseField MIN_CHILDREN_FIELD = new ParseField("min_children");
    private static final ParseField SCORE_MODE_FIELD = new ParseField("score_mode");
    private static final ParseField INNER_HITS_FIELD = new ParseField("inner_hits");

    private final QueryBuilder<?> query;

    private final String type;

    private ScoreMode scoreMode = DEFAULT_SCORE_MODE;

    private int minChildren = DEFAULT_MIN_CHILDREN;

    private int maxChildren = DEFAULT_MAX_CHILDREN;

    private InnerHitBuilder innerHitBuilder;


    public HasChildQueryBuilder(String type, QueryBuilder<?> query, int maxChildren, int minChildren, ScoreMode scoreMode,
                                InnerHitBuilder innerHitBuilder) {
        this(type, query);
        scoreMode(scoreMode);
        this.maxChildren = maxChildren;
        this.minChildren = minChildren;
        this.innerHitBuilder = innerHitBuilder;
        if (this.innerHitBuilder != null) {
            this.innerHitBuilder.setParentChildType(type);
            this.innerHitBuilder.setQuery(query);
        }
    }

    public HasChildQueryBuilder(String type, QueryBuilder<?> query) {
        if (type == null) {
            throw new IllegalArgumentException("[" + NAME + "] requires 'type' field");
        }
        if (query == null) {
            throw new IllegalArgumentException("[" + NAME + "] requires 'query' field");
        }
        this.type = type;
        this.query = query;
    }

    /**
     * Read from a stream.
     */
    public HasChildQueryBuilder(StreamInput in) throws IOException {
        super(in);
        type = in.readString();
        minChildren = in.readInt();
        maxChildren = in.readInt();
        scoreMode = ScoreMode.values()[in.readVInt()];
        query = in.readQuery();
        innerHitBuilder = in.readOptionalWriteable(InnerHitBuilder::new);
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(type);
        out.writeInt(minChildren());
        out.writeInt(maxChildren());
        out.writeVInt(scoreMode.ordinal());
        out.writeQuery(query);
        out.writeOptionalWriteable(innerHitBuilder);
    }

    /**
     * Defines how the scores from the matching child documents are mapped into the parent document.
     */
    public HasChildQueryBuilder scoreMode(ScoreMode scoreMode) {
        if (scoreMode == null) {
            throw new IllegalArgumentException("[" + NAME + "]  requires 'score_mode' field");
        }
        this.scoreMode = scoreMode;
        return this;
    }

    /**
     * Defines the minimum number of children that are required to match for the parent to be considered a match.
     */
    public HasChildQueryBuilder minChildren(int minChildren) {
        if (minChildren < 0) {
            throw new IllegalArgumentException("[" + NAME + "]  requires non-negative 'min_children' field");
        }
        this.minChildren = minChildren;
        return this;
    }

    /**
     * Defines the maximum number of children that are required to match for the parent to be considered a match.
     */
    public HasChildQueryBuilder maxChildren(int maxChildren) {
        if (maxChildren < 0) {
            throw new IllegalArgumentException("[" + NAME + "]  requires non-negative 'max_children' field");
        }
        this.maxChildren = maxChildren;
        return this;
    }

    /**
     * Sets the query name for the filter that can be used when searching for matched_filters per hit.
     */
    public HasChildQueryBuilder innerHit(InnerHitBuilder innerHitBuilder) {
        this.innerHitBuilder = Objects.requireNonNull(innerHitBuilder);
        this.innerHitBuilder.setParentChildType(type);
        this.innerHitBuilder.setQuery(query);
        return this;
    }

    /**
     * Returns inner hit definition in the scope of this query and reusing the defined type and query.
     */
    public InnerHitBuilder innerHit() {
        return innerHitBuilder;
    }

    /**
     * Returns the children query to execute.
     */
    public QueryBuilder<?> query() {
        return query;
    }

    /**
     * Returns the child type
     */
    public String childType() {
        return type;
    }

    /**
     * Returns how the scores from the matching child documents are mapped into the parent document.
     */
    public ScoreMode scoreMode() {
        return scoreMode;
    }

    /**
     * Returns the minimum number of children that are required to match for the parent to be considered a match.
     * The default is {@value #DEFAULT_MAX_CHILDREN}
     */
    public int minChildren() {
        return minChildren;
    }

    /**
     * Returns the maximum number of children that are required to match for the parent to be considered a match.
     * The default is {@value #DEFAULT_MIN_CHILDREN}
     */
    public int maxChildren() { return maxChildren; }

    @Override
    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.field(QUERY_FIELD.getPreferredName());
        query.toXContent(builder, params);
        builder.field(TYPE_FIELD.getPreferredName(), type);
        builder.field(SCORE_MODE_FIELD.getPreferredName(), scoreModeAsString(scoreMode));
        builder.field(MIN_CHILDREN_FIELD.getPreferredName(), minChildren);
        builder.field(MAX_CHILDREN_FIELD.getPreferredName(), maxChildren);
        printBoostAndQueryName(builder);
        if (innerHitBuilder != null) {
            builder.field(INNER_HITS_FIELD.getPreferredName(), innerHitBuilder, params);
        }
        builder.endObject();
    }

    public static HasChildQueryBuilder fromXContent(QueryParseContext parseContext) throws IOException {
        XContentParser parser = parseContext.parser();
        float boost = AbstractQueryBuilder.DEFAULT_BOOST;
        String childType = null;
        ScoreMode scoreMode = HasChildQueryBuilder.DEFAULT_SCORE_MODE;
        int minChildren = HasChildQueryBuilder.DEFAULT_MIN_CHILDREN;
        int maxChildren = HasChildQueryBuilder.DEFAULT_MAX_CHILDREN;
        String queryName = null;
        InnerHitBuilder innerHitBuilder = null;
        String currentFieldName = null;
        XContentParser.Token token;
        QueryBuilder<?> iqb = null;
        while ((token = parser.nextToken()) != XContentParser.Token.END_OBJECT) {
            if (token == XContentParser.Token.FIELD_NAME) {
                currentFieldName = parser.currentName();
            } else if (parseContext.isDeprecatedSetting(currentFieldName)) {
                // skip
            } else if (token == XContentParser.Token.START_OBJECT) {
                if (parseContext.parseFieldMatcher().match(currentFieldName, QUERY_FIELD)) {
                    iqb = parseContext.parseInnerQueryBuilder();
                } else if (parseContext.parseFieldMatcher().match(currentFieldName, INNER_HITS_FIELD)) {
                    innerHitBuilder = InnerHitBuilder.fromXContent(parser, parseContext);
                } else {
                    throw new ParsingException(parser.getTokenLocation(), "[has_child] query does not support [" + currentFieldName + "]");
                }
            } else if (token.isValue()) {
                if (parseContext.parseFieldMatcher().match(currentFieldName, TYPE_FIELD)) {
                    childType = parser.text();
                } else if (parseContext.parseFieldMatcher().match(currentFieldName, SCORE_MODE_FIELD)) {
                    scoreMode = parseScoreMode(parser.text());
                } else if (parseContext.parseFieldMatcher().match(currentFieldName, AbstractQueryBuilder.BOOST_FIELD)) {
                    boost = parser.floatValue();
                } else if (parseContext.parseFieldMatcher().match(currentFieldName, MIN_CHILDREN_FIELD)) {
                    minChildren = parser.intValue(true);
                } else if (parseContext.parseFieldMatcher().match(currentFieldName, MAX_CHILDREN_FIELD)) {
                    maxChildren = parser.intValue(true);
                } else if (parseContext.parseFieldMatcher().match(currentFieldName, AbstractQueryBuilder.NAME_FIELD)) {
                    queryName = parser.text();
                } else {
                    throw new ParsingException(parser.getTokenLocation(), "[has_child] query does not support [" + currentFieldName + "]");
                }
            }
        }
        HasChildQueryBuilder hasChildQueryBuilder = new HasChildQueryBuilder(childType, iqb, maxChildren, minChildren,
                scoreMode, innerHitBuilder);
        hasChildQueryBuilder.queryName(queryName);
        hasChildQueryBuilder.boost(boost);
        return hasChildQueryBuilder;
    }

    public static ScoreMode parseScoreMode(String scoreModeString) {
        if ("none".equals(scoreModeString)) {
            return ScoreMode.None;
        } else if ("min".equals(scoreModeString)) {
            return ScoreMode.Min;
        } else if ("max".equals(scoreModeString)) {
            return ScoreMode.Max;
        } else if ("avg".equals(scoreModeString)) {
            return ScoreMode.Avg;
        } else if ("sum".equals(scoreModeString)) {
            return ScoreMode.Total;
        }
        throw new IllegalArgumentException("No score mode for child query [" + scoreModeString + "] found");
    }

    public static String scoreModeAsString(ScoreMode scoreMode) {
        if (scoreMode == ScoreMode.Total) {
            // Lucene uses 'total' but 'sum' is more consistent with other elasticsearch APIs
            return "sum";
        } else {
            return scoreMode.name().toLowerCase(Locale.ROOT);
        }
    }

    @Override
    public String getWriteableName() {
        return NAME;
    }

    @Override
    protected Query doToQuery(QueryShardContext context) throws IOException {
        Query innerQuery;
        final String[] previousTypes = context.getTypes();
        context.setTypes(type);
        try {
            innerQuery = query.toQuery(context);
        } finally {
            context.setTypes(previousTypes);
        }

        if (innerQuery == null) {
            return null;
        }
        DocumentMapper childDocMapper = context.getMapperService().documentMapper(type);
        if (childDocMapper == null) {
            throw new QueryShardException(context, "[" + NAME + "] no mapping found for type [" + type + "]");
        }
        ParentFieldMapper parentFieldMapper = childDocMapper.parentFieldMapper();
        if (parentFieldMapper.active() == false) {
            throw new QueryShardException(context, "[" + NAME + "] _parent field has no parent type configured");
        }
        if (innerHitBuilder != null) {
            context.addInnerHit(innerHitBuilder);
        }

        String parentType = parentFieldMapper.type();
        DocumentMapper parentDocMapper = context.getMapperService().documentMapper(parentType);
        if (parentDocMapper == null) {
            throw new QueryShardException(context, "[" + NAME + "] Type [" + type + "] points to a non existent parent type ["
                    + parentType + "]");
        }

        if (maxChildren > 0 && maxChildren < minChildren) {
            throw new QueryShardException(context, "[" + NAME + "] 'max_children' is less than 'min_children'");
        }

        // wrap the query with type query
        innerQuery = Queries.filtered(innerQuery, childDocMapper.typeFilter());

        final ParentChildIndexFieldData parentChildIndexFieldData = context.getForField(parentFieldMapper.fieldType());
        return new LateParsingQuery(parentDocMapper.typeFilter(), innerQuery, minChildren(), maxChildren(),
                                    parentType, scoreMode, parentChildIndexFieldData, context.getSearchSimilarity());
    }

    final static class LateParsingQuery extends Query {

        private final Query toQuery;
        private final Query innerQuery;
        private final int minChildren;
        private final int maxChildren;
        private final String parentType;
        private final ScoreMode scoreMode;
        private final ParentChildIndexFieldData parentChildIndexFieldData;
        private final Similarity similarity;

        LateParsingQuery(Query toQuery, Query innerQuery, int minChildren, int maxChildren,
                         String parentType, ScoreMode scoreMode, ParentChildIndexFieldData parentChildIndexFieldData,
                         Similarity similarity) {
            this.toQuery = toQuery;
            this.innerQuery = innerQuery;
            this.minChildren = minChildren;
            this.maxChildren = maxChildren;
            this.parentType = parentType;
            this.scoreMode = scoreMode;
            this.parentChildIndexFieldData = parentChildIndexFieldData;
            this.similarity = similarity;
        }

        @Override
        public Query rewrite(IndexReader reader) throws IOException {
            Query rewritten = super.rewrite(reader);
            if (rewritten != this) {
                return rewritten;
            }
            if (reader instanceof DirectoryReader) {
                String joinField = ParentFieldMapper.joinField(parentType);
                IndexSearcher indexSearcher = new IndexSearcher(reader);
                indexSearcher.setQueryCache(null);
                indexSearcher.setSimilarity(similarity);
                IndexParentChildFieldData indexParentChildFieldData = parentChildIndexFieldData.loadGlobal((DirectoryReader) reader);
                MultiDocValues.OrdinalMap ordinalMap = ParentChildIndexFieldData.getOrdinalMap(indexParentChildFieldData, parentType);
                return JoinUtil.createJoinQuery(joinField, innerQuery, toQuery, indexSearcher, scoreMode,
                        ordinalMap, minChildren, maxChildren);
            } else {
                if (reader.leaves().isEmpty() && reader.numDocs() == 0) {
                    // asserting reader passes down a MultiReader during rewrite which makes this
                    // blow up since for this query to work we have to have a DirectoryReader otherwise
                    // we can't load global ordinals - for this to work we simply check if the reader has no leaves
                    // and rewrite to match nothing
                    return new MatchNoDocsQuery();
                }
                throw new IllegalStateException("can't load global ordinals for reader of type: " +
                        reader.getClass() + " must be a DirectoryReader");
            }
        }

        @Override
        public boolean equals(Object o) {
            if (!super.equals(o)) return false;

            LateParsingQuery that = (LateParsingQuery) o;

            if (minChildren != that.minChildren) return false;
            if (maxChildren != that.maxChildren) return false;
            if (!toQuery.equals(that.toQuery)) return false;
            if (!innerQuery.equals(that.innerQuery)) return false;
            if (!parentType.equals(that.parentType)) return false;
            return scoreMode == that.scoreMode;
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), toQuery, innerQuery, minChildren, maxChildren, parentType, scoreMode);
        }

        @Override
        public String toString(String s) {
            return "LateParsingQuery {parentType=" + parentType + "}";
        }

        public int getMinChildren() {
            return minChildren;
        }

        public int getMaxChildren() {
            return maxChildren;
        }

        public ScoreMode getScoreMode() {
            return scoreMode;
        }

        public Query getInnerQuery() {
            return innerQuery;
        }

        public Similarity getSimilarity() {
            return similarity;
        }
    }

    @Override
    protected boolean doEquals(HasChildQueryBuilder that) {
        return Objects.equals(query, that.query)
                && Objects.equals(type, that.type)
                && Objects.equals(scoreMode, that.scoreMode)
                && Objects.equals(minChildren, that.minChildren)
                && Objects.equals(maxChildren, that.maxChildren)
                && Objects.equals(innerHitBuilder, that.innerHitBuilder);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(query, type, scoreMode, minChildren, maxChildren, innerHitBuilder);
    }

    @Override
    protected QueryBuilder<?> doRewrite(QueryRewriteContext queryRewriteContext) throws IOException {
        QueryBuilder<?> rewrite = query.rewrite(queryRewriteContext);
        if (rewrite != query) {
            HasChildQueryBuilder hasChildQueryBuilder = new HasChildQueryBuilder(type, rewrite);
            hasChildQueryBuilder.minChildren(minChildren);
            hasChildQueryBuilder.maxChildren(maxChildren);
            hasChildQueryBuilder.scoreMode(scoreMode);
            hasChildQueryBuilder.innerHit(innerHitBuilder);
            return hasChildQueryBuilder;
        }
        return this;
    }
}
