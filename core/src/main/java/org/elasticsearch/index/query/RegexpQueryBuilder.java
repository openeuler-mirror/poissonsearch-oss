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
import org.apache.lucene.search.Query;
import org.apache.lucene.search.RegexpQuery;
import org.apache.lucene.util.automaton.Operations;
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
 * A Query that does fuzzy matching for a specific value.
 */
public class RegexpQueryBuilder extends AbstractQueryBuilder<RegexpQueryBuilder> implements MultiTermQueryBuilder<RegexpQueryBuilder> {

    public static final String NAME = "regexp";

    public static final int DEFAULT_FLAGS_VALUE = RegexpFlag.ALL.value();

    public static final int DEFAULT_MAX_DETERMINIZED_STATES = Operations.DEFAULT_MAX_DETERMINIZED_STATES;

    private final String fieldName;
    
    private final String value;
    
    private int flagsValue = DEFAULT_FLAGS_VALUE;
    
    private int maxDeterminizedStates = DEFAULT_MAX_DETERMINIZED_STATES;
    
    private String rewrite;
    
    static final RegexpQueryBuilder PROTOTYPE = new RegexpQueryBuilder(null, null);

    /**
     * Constructs a new regex query.
     * 
     * @param fieldName  The name of the field
     * @param value The regular expression
     */
    public RegexpQueryBuilder(String fieldName, String value) {
        this.fieldName = fieldName;
        this.value = value;
    }

    /** Returns the field name used in this query. */
    public String fieldName() {
        return this.fieldName;
    }

    /**
     *  Returns the value used in this query.
     */
    public String value() {
        return this.value;
    }

    public RegexpQueryBuilder flags(RegexpFlag... flags) {
        if (flags == null) {
            this.flagsValue = DEFAULT_FLAGS_VALUE;
            return this;
        }
        int value = 0;
        if (flags.length == 0) {
            value = RegexpFlag.ALL.value;
        } else {
            for (RegexpFlag flag : flags) {
                value |= flag.value;
            }
        }
        this.flagsValue = value;
        return this;
    }

    public RegexpQueryBuilder flags(int flags) {
        this.flagsValue = flags;
        return this;
    }

    public int flags() {
        return this.flagsValue;
    }

    /**
     * Sets the regexp maxDeterminizedStates.
     */
    public RegexpQueryBuilder maxDeterminizedStates(int value) {
        this.maxDeterminizedStates = value;
        return this;
    }
    
    public int maxDeterminizedStates() {
        return this.maxDeterminizedStates;
    }

    public RegexpQueryBuilder rewrite(String rewrite) {
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
        builder.field("value", this.value);
        builder.field("flags_value", flagsValue);
        builder.field("max_determinized_states", maxDeterminizedStates);
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
    public Query doToQuery(QueryParseContext parseContext) throws QueryParsingException, IOException {
        MultiTermQuery.RewriteMethod method = QueryParsers.parseRewriteMethod(parseContext.parseFieldMatcher(), rewrite, null);

        Query query = null;
        MappedFieldType fieldType = parseContext.fieldMapper(fieldName);
        if (fieldType != null) {
            query = fieldType.regexpQuery(value, flagsValue, maxDeterminizedStates, method, parseContext);
        }
        if (query == null) {
            RegexpQuery regexpQuery = new RegexpQuery(new Term(fieldName, BytesRefs.toBytesRef(value)), flagsValue, maxDeterminizedStates);
            if (method != null) {
                regexpQuery.setRewriteMethod(method);
            }
            query = regexpQuery;
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
    public RegexpQueryBuilder doReadFrom(StreamInput in) throws IOException {
        RegexpQueryBuilder regexpQueryBuilder = new RegexpQueryBuilder(in.readString(), in.readString());
        regexpQueryBuilder.flagsValue = in.readVInt();
        regexpQueryBuilder.maxDeterminizedStates = in.readVInt();
        regexpQueryBuilder.rewrite = in.readOptionalString();
        return regexpQueryBuilder;
    }

    @Override
    public void doWriteTo(StreamOutput out) throws IOException {
        out.writeString(fieldName);
        out.writeString(value);
        out.writeVInt(flagsValue);
        out.writeVInt(maxDeterminizedStates);
        out.writeOptionalString(rewrite);
    }

    @Override
    public int doHashCode() {
        return Objects.hash(fieldName, value, flagsValue, maxDeterminizedStates, rewrite);
    }

    @Override
    public boolean doEquals(RegexpQueryBuilder other) {
        return Objects.equals(fieldName, other.fieldName) &&
                Objects.equals(value, other.value) &&
                Objects.equals(flagsValue, other.flagsValue) &&
                Objects.equals(maxDeterminizedStates, other.maxDeterminizedStates) &&
                Objects.equals(rewrite, other.rewrite);
    }
}
