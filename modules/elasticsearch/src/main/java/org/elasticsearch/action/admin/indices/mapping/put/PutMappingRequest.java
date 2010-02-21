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

package org.elasticsearch.action.admin.indices.mapping.put;

import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.master.MasterNodeOperationRequest;
import org.elasticsearch.util.Required;
import org.elasticsearch.util.TimeValue;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.action.Actions.*;
import static org.elasticsearch.util.TimeValue.*;

/**
 * @author kimchy (Shay Banon)
 */
public class PutMappingRequest extends MasterNodeOperationRequest {

    private String[] indices;

    private String mappingType;

    private String mappingSource;

    private TimeValue timeout = new TimeValue(10, TimeUnit.SECONDS);

    private boolean ignoreDuplicates = true;

    PutMappingRequest() {
    }

    public PutMappingRequest(String... indices) {
        this.indices = indices;
    }

    public PutMappingRequest(String index, String mappingType, String mappingSource) {
        this(new String[]{index}, mappingType, mappingSource);
    }

    public PutMappingRequest(String[] indices, String mappingType, String mappingSource) {
        this.indices = indices;
        this.mappingType = mappingType;
        this.mappingSource = mappingSource;
    }

    @Override public ActionRequestValidationException validate() {
        ActionRequestValidationException validationException = null;
        if (mappingSource == null) {
            validationException = addValidationError("mapping source is missing", validationException);
        }
        return validationException;
    }

    @Override public PutMappingRequest listenerThreaded(boolean threadedListener) {
        return this;
    }

    String[] indices() {
        return indices;
    }

    String type() {
        return mappingType;
    }

    /**
     * The type of the mappings. Not required since it can be defined explicitly within the mapping source.
     * If it is not defined within the mapping source, then it is required.
     */
    public PutMappingRequest type(String mappingType) {
        this.mappingType = mappingType;
        return this;
    }

    String mappingSource() {
        return mappingSource;
    }

    @Required public PutMappingRequest mappingSource(String mappingSource) {
        this.mappingSource = mappingSource;
        return this;
    }

    TimeValue timeout() {
        return timeout;
    }

    public PutMappingRequest timeout(TimeValue timeout) {
        this.timeout = timeout;
        return this;
    }

    public boolean ignoreDuplicates() {
        return ignoreDuplicates;
    }

    public PutMappingRequest ignoreDuplicates(boolean ignoreDuplicates) {
        this.ignoreDuplicates = ignoreDuplicates;
        return this;
    }

    @Override public void readFrom(DataInput in) throws IOException, ClassNotFoundException {
        super.readFrom(in);
        indices = new String[in.readInt()];
        for (int i = 0; i < indices.length; i++) {
            indices[i] = in.readUTF();
        }
        if (in.readBoolean()) {
            mappingType = in.readUTF();
        }
        mappingSource = in.readUTF();
        timeout = readTimeValue(in);
        ignoreDuplicates = in.readBoolean();
    }

    @Override public void writeTo(DataOutput out) throws IOException {
        super.writeTo(out);
        if (indices == null) {
            out.writeInt(0);
        } else {
            out.writeInt(indices.length);
            for (String index : indices) {
                out.writeUTF(index);
            }
        }
        if (mappingType == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(mappingType);
        }
        out.writeUTF(mappingSource);
        timeout.writeTo(out);
        out.writeBoolean(ignoreDuplicates);
    }
}