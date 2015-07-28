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

import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.spans.SpanMultiTermQueryWrapper;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Objects;

/**
 * Query that allows wraping a {@link MultiTermQueryBuilder} (one of wildcard, fuzzy, prefix, term, range or regexp query)
 * as a {@link SpanQueryBuilder} so it can be nested.
 */
public class SpanMultiTermQueryBuilder extends AbstractQueryBuilder<SpanMultiTermQueryBuilder> implements SpanQueryBuilder<SpanMultiTermQueryBuilder> {

    public static final String NAME = "span_multi";
    private final MultiTermQueryBuilder multiTermQueryBuilder;
    static final SpanMultiTermQueryBuilder PROTOTYPE = new SpanMultiTermQueryBuilder(null);

    public SpanMultiTermQueryBuilder(MultiTermQueryBuilder multiTermQueryBuilder) {
        this.multiTermQueryBuilder = multiTermQueryBuilder;
    }

    public MultiTermQueryBuilder multiTermQueryBuilder() {
        return this.multiTermQueryBuilder;
    }

    @Override
    protected void doXContent(XContentBuilder builder, Params params)
            throws IOException {
        builder.startObject(NAME);
        builder.field(SpanMultiTermQueryParser.MATCH_NAME);
        multiTermQueryBuilder.toXContent(builder, params);
        printBoostAndQueryName(builder);
        builder.endObject();
    }

    @Override
    protected Query doToQuery(QueryParseContext parseContext) throws IOException {
        Query subQuery = multiTermQueryBuilder.toQuery(parseContext);
        if (subQuery instanceof MultiTermQuery == false) {
            throw new UnsupportedOperationException("unsupported inner query, should be " + MultiTermQuery.class.getName() +" but was "
                    + subQuery.getClass().getName());
        }
        return new SpanMultiTermQueryWrapper<>((MultiTermQuery) subQuery);
    }

    @Override
    public QueryValidationException validate() {
        QueryValidationException validationException = null;
        if (multiTermQueryBuilder == null) {
            validationException = addValidationError("inner clause ["+ SpanMultiTermQueryParser.MATCH_NAME +"] cannot be null.", validationException);
        } else {
            validationException = validateInnerQuery(multiTermQueryBuilder, validationException);
        }
        return validationException;
    }

    @Override
    protected SpanMultiTermQueryBuilder doReadFrom(StreamInput in) throws IOException {
        MultiTermQueryBuilder multiTermBuilder = in.readNamedWriteable();
        return new SpanMultiTermQueryBuilder(multiTermBuilder);
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeNamedWriteable(multiTermQueryBuilder);
    }

    @Override
    protected int doHashCode() {
        return Objects.hash(multiTermQueryBuilder);
    }

    @Override
    protected boolean doEquals(SpanMultiTermQueryBuilder other) {
        return Objects.equals(multiTermQueryBuilder, other.multiTermQueryBuilder);
    }

    @Override
    public String getName() {
        return NAME;
    }
}
