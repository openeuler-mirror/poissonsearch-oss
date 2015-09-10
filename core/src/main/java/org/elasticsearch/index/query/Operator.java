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

import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.util.CollectionUtils;

import java.io.IOException;

public enum Operator implements Writeable<Operator> {
    OR(0), AND(1);

    private final int ordinal;

    private static final Operator PROTOTYPE = OR;

    Operator(int ordinal) {
        this.ordinal = ordinal;
    }

    public BooleanClause.Occur toBooleanClauseOccur() {
        switch (this) {
            case OR:
                return BooleanClause.Occur.SHOULD;
            case AND:
                return BooleanClause.Occur.MUST;
            default:
                throw Operator.newOperatorException(this.toString());
        }
    }

    public QueryParser.Operator toQueryParserOperator() {
        switch (this) {
            case OR:
                return QueryParser.Operator.OR;
            case AND:
                return QueryParser.Operator.AND;
            default:
                throw Operator.newOperatorException(this.toString());
        }
    }

    @Override
    public Operator readFrom(StreamInput in) throws IOException {
        int ord = in.readVInt();
        for (Operator operator : Operator.values()) {
            if (operator.ordinal == ord) {
                return operator;
            }
        }
        throw new ElasticsearchException("unknown serialized operator [" + ord + "]");
    }

    public static Operator readOperatorFrom(StreamInput in) throws IOException {
        return PROTOTYPE.readFrom(in);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(this.ordinal);
    }

    public static Operator fromString(String op) {
        for (Operator operator : Operator.values()) {
            if (operator.name().equalsIgnoreCase(op)) {
                return operator;
            }
        }
        throw Operator.newOperatorException(op);
    }

    private static IllegalArgumentException newOperatorException(String op) {
        return new IllegalArgumentException("operator needs to be either " + CollectionUtils.arrayAsArrayList(Operator.values()) + ", but not [" + op + "]");
    }
}
