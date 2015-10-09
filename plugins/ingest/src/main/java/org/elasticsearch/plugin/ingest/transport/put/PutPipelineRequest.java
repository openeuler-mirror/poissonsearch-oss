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

package org.elasticsearch.plugin.ingest.transport.put;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;

import java.io.IOException;

import static org.elasticsearch.action.ValidateActions.addValidationError;

public class PutPipelineRequest extends ActionRequest {

    private String id;
    private BytesReference source;

    @Override
    public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (id == null) {
            validationException = addValidationError("id is missing", validationException);
        }
        if (source == null) {
            validationException = addValidationError("source is missing", validationException);
        }
        return validationException;
    }

    public String id() {
        return id;
    }

    public void id(String id) {
        this.id = id;
    }

    public BytesReference source() {
        return source;
    }

    public void source(BytesReference source) {
        this.source = source;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        id = in.readString();
        source = in.readBytesReference();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeString(id);
        out.writeBytesReference(source);
    }
}
