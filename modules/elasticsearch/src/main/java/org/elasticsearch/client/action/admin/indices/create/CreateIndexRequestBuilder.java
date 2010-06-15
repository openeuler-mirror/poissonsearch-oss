/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
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

package org.elasticsearch.client.action.admin.indices.create;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.ListenableActionFuture;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexResponse;
import org.elasticsearch.action.support.PlainListenableActionFuture;
import org.elasticsearch.client.internal.InternalIndicesAdminClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.builder.XContentBuilder;
import org.elasticsearch.util.TimeValue;

import java.util.Map;

/**
 * @author kimchy (shay.banon)
 */
public class CreateIndexRequestBuilder {

    private final InternalIndicesAdminClient indicesClient;

    private final CreateIndexRequest request;

    public CreateIndexRequestBuilder(InternalIndicesAdminClient indicesClient, String index) {
        this.indicesClient = indicesClient;
        this.request = new CreateIndexRequest(index);
    }

    /**
     * The settings to created the index with.
     */
    public CreateIndexRequestBuilder setSettings(Settings settings) {
        request.settings(settings);
        return this;
    }

    /**
     * The settings to created the index with.
     */
    public CreateIndexRequestBuilder setSettings(Settings.Builder settings) {
        request.settings(settings);
        return this;
    }

    /**
     * The settings to crete the index with (either json/yaml/properties format)
     */
    public CreateIndexRequestBuilder setSettings(String source) {
        request.settings(source);
        return this;
    }

    /**
     * The settings to crete the index with (either json/yaml/properties format)
     */
    public CreateIndexRequestBuilder setSettings(Map<String, Object> source) {
        request.settings(source);
        return this;
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * @param type   The mapping type
     * @param source The mapping source
     */
    public CreateIndexRequestBuilder addMapping(String type, String source) {
        request.mapping(type, source);
        return this;
    }

    /**
     * The cause for this index creation.
     */
    public CreateIndexRequestBuilder cause(String cause) {
        request.cause(cause);
        return this;
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * @param type   The mapping type
     * @param source The mapping source
     */
    public CreateIndexRequestBuilder addMapping(String type, XContentBuilder source) {
        request.mapping(type, source);
        return this;
    }

    /**
     * Adds mapping that will be added when the index gets created.
     *
     * @param type   The mapping type
     * @param source The mapping source
     */
    public CreateIndexRequestBuilder mapping(String type, Map<String, Object> source) {
        request.mapping(type, source);
        return this;
    }

    /**
     * Timeout to wait for the index creation to be acknowledged by current cluster nodes. Defaults
     * to <tt>10s</tt>.
     */
    public CreateIndexRequestBuilder setTimeout(TimeValue timeout) {
        request.timeout(timeout);
        return this;
    }

    /**
     * Timeout to wait for the index creation to be acknowledged by current cluster nodes. Defaults
     * to <tt>10s</tt>.
     */
    public CreateIndexRequestBuilder setTimeout(String timeout) {
        request.timeout(timeout);
        return this;
    }

    /**
     * Executes the operation asynchronously and returns a future.
     */
    public ListenableActionFuture<CreateIndexResponse> execute() {
        PlainListenableActionFuture<CreateIndexResponse> future = new PlainListenableActionFuture<CreateIndexResponse>(request.listenerThreaded(), indicesClient.threadPool());
        indicesClient.create(request, future);
        return future;
    }

    /**
     * Executes the operation asynchronously with the provided listener.
     */
    public void execute(ActionListener<CreateIndexResponse> listener) {
        indicesClient.create(request, listener);
    }
}
