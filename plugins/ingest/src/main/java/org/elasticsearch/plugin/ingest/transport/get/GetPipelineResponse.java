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

package org.elasticsearch.plugin.ingest.transport.get;

import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.StatusToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.plugin.ingest.PipelineDefinition;
import org.elasticsearch.rest.RestStatus;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class GetPipelineResponse extends ActionResponse implements StatusToXContent {

    private List<PipelineDefinition> pipelines;

    public GetPipelineResponse() {
    }

    public GetPipelineResponse(List<PipelineDefinition> pipelines) {
        this.pipelines = pipelines;
    }

    public List<PipelineDefinition> pipelines() {
        return pipelines;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        int size = in.readVInt();
        pipelines = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            pipelines.add(PipelineDefinition.readPipelineDefinitionFrom(in));
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeVInt(pipelines.size());
        for (PipelineDefinition pipeline : pipelines) {
            pipeline.writeTo(out);
        }
    }

    public boolean isFound() {
        return !pipelines.isEmpty();
    }

    @Override
    public RestStatus status() {
        return isFound() ? RestStatus.OK : RestStatus.NOT_FOUND;
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        for (PipelineDefinition definition : pipelines) {
            definition.toXContent(builder, params);
        }
        return builder;
    }
}
