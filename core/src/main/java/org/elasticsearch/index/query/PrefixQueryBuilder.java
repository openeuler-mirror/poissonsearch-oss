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

import org.apache.lucene.index.Term;
import org.apache.lucene.search.MultiTermQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.lucene.BytesRefs;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.query.support.QueryParsers;

import java.io.IOException;
import java.util.Objects;

/**
 * A Query that matches documents containing terms with a specified prefix.
 */
public class PrefixQueryBuilder extends AbstractQueryBuilder<PrefixQueryBuilder> implements MultiTermQueryBuilder<PrefixQueryBuilder> {

    public static final String NAME = "prefix";

    private final String fieldName;
    
    private final String value;
    
    private String rewrite;

    static final PrefixQueryBuilder PROTOTYPE = new PrefixQueryBuilder(null, null);

    /**
     * A Query that matches documents containing terms with a specified prefix.
     *
     * @param fieldName The name of the field
     * @param value The prefix query
     */
    public PrefixQueryBuilder(String fieldName, String value) {
        this.fieldName = fieldName;
        this.value = value;
    }
    
    public String fieldName() {
        return this.fieldName;
    }

    public String value() {
        return this.value;
    }

    public PrefixQueryBuilder rewrite(String rewrite) {
        this.rewrite = rewrite;
        return this;
    }

    public String rewrite() {
        return this.rewrite;
    }

    @Override
    public void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(NAME);
        builder.startObject(fieldName);
        builder.field("prefix", this.value);
        if (rewrite != null) {
            builder.field("rewrite", rewrite);
        }
        printBoostAndQueryName(builder);
        builder.endObject();
        builder.endObject();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected Query doToQuery(QueryParseContext parseContext) throws IOException {
        MultiTermQuery.RewriteMethod method = QueryParsers.parseRewriteMethod(parseContext.parseFieldMatcher(), rewrite, null);

        Query query = null;
        MappedFieldType fieldType = parseContext.fieldMapper(fieldName);
        if (fieldType != null) {
            query = fieldType.prefixQuery(value, method, parseContext);
        }
        if (query == null) {
            PrefixQuery prefixQuery = new PrefixQuery(new Term(fieldName, BytesRefs.toBytesRef(value)));
            if (method != null) {
                prefixQuery.setRewriteMethod(method);
            }
            query = prefixQuery;
        }
        
        return query;
    }

    @Override
    public QueryValidationException validate() {
        QueryValidationException validationException = null;
        if (Strings.isEmpty(this.fieldName)) {
            validationException = addValidationError("field name cannot be null or empty.", validationException);
        }
        if (this.value == null) {
            validationException = addValidationError("query text cannot be null", validationException);
        }
        return validationException;
    }

    @Override
    protected PrefixQueryBuilder doReadFrom(StreamInput in) throws IOException {
        PrefixQueryBuilder prefixQueryBuilder = new PrefixQueryBuilder(in.readString(), in.readString());
        prefixQueryBuilder.rewrite = in.readOptionalString();
        return prefixQueryBuilder;
    }

    @Override
    protected void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(fieldName);
        out.writeString(value);
        out.writeOptionalString(rewrite);
    }

    @Override
    protected final int doHashCode() {
        return Objects.hash(fieldName, value, rewrite);
    }

    @Override
    protected boolean doEquals(PrefixQueryBuilder other) {
        return Objects.equals(fieldName, other.fieldName) && 
                Objects.equals(value, other.value) &&
                Objects.equals(rewrite, other.rewrite);
    }
}
