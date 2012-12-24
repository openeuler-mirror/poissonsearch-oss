/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.query;

import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;

/**
 * Builder for the 'has_parent' query.
 */
public class HasParentQueryBuilder extends BaseQueryBuilder implements BoostableQueryBuilder<HasParentQueryBuilder> {

    private final QueryBuilder queryBuilder;
    private final String parentType;
    private String scoreType;
    private String executionType;
    private String scope;
    private float boost = 1.0f;

    /**
     * @param parentType  The parent type
     * @param parentQuery The query that will be matched with parent documents
     */
    public HasParentQueryBuilder(String parentType, QueryBuilder parentQuery) {
        this.parentType = parentType;
        this.queryBuilder = parentQuery;
    }

    public HasParentQueryBuilder scope(String scope) {
        this.scope = scope;
        return this;
    }

    public HasParentQueryBuilder boost(float boost) {
        this.boost = boost;
        return this;
    }

    /**
     * Defines how the parent score is mapped into the child documents.
     */
    public HasParentQueryBuilder scoreType(String scoreType) {
        this.scoreType = scoreType;
        return this;
    }

    /**
     * Expert: Sets the low level child to parent filtering implementation. Can be: 'bitset' or 'uid'
     * <p/>
     * Only applicable when score_type is set to none.
     * This option is experimental and will be removed.
     */
    public HasParentQueryBuilder executionType(String executionType) {
        this.executionType = executionType;
        return this;
    }

    protected void doXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(HasParentQueryParser.NAME);
        builder.field("query");
        queryBuilder.toXContent(builder, params);
        builder.field("parent_type", parentType);
        if (scope != null) {
            builder.field("_scope", scope);
        }
        if (scoreType != null) {
            builder.field("score_type", scoreType);
        }
        if (executionType != null) {
            builder.field("execution_type", executionType);
        }
        if (boost != 1.0f) {
            builder.field("boost", boost);
        }
        builder.endObject();
    }
}

