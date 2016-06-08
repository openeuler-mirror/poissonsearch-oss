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
package org.elasticsearch.action.admin.indices.rollover;

import org.elasticsearch.action.admin.indices.alias.Alias;
import org.elasticsearch.action.support.master.MasterNodeOperationRequestBuilder;
import org.elasticsearch.client.ElasticsearchClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;


/**
 * TODO: Documentation
 */
public class RolloverRequestBuilder extends MasterNodeOperationRequestBuilder<RolloverRequest, RolloverResponse,
    RolloverRequestBuilder> {
    public RolloverRequestBuilder(ElasticsearchClient client, RolloverAction action) {
        super(client, action, new RolloverRequest());
    }

    public RolloverRequestBuilder setAlias(String sourceAlias) {
        this.request.setAlias(sourceAlias);
        return this;
    }

    public RolloverRequestBuilder addMaxIndexAgeCondition(TimeValue age) {
        this.request.addMaxIndexAgeCondition(age);
        return this;
    }

    public RolloverRequestBuilder addMaxIndexDocsCondition(long docs) {
        this.request.addMaxIndexDocsCondition(docs);
        return this;
    }

    public RolloverRequestBuilder simulate(boolean simulate) {
        this.request.simulate(simulate);
        return this;
    }

    public RolloverRequestBuilder settings(Settings settings) {
        this.request.getCreateIndexRequest().settings(settings);
        return this;
    }

    public RolloverRequestBuilder alias(Alias alias) {
        this.request.getCreateIndexRequest().alias(alias);
        return this;
    }

    public RolloverRequestBuilder mapping(String type, String source) {
        this.request.getCreateIndexRequest().mapping(type, source);
        return this;
    }
}
