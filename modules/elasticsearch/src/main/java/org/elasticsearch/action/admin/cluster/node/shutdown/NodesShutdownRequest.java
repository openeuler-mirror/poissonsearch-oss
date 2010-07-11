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

package org.elasticsearch.action.admin.cluster.node.shutdown;

import org.elasticsearch.action.ActionRequestValidationException;
import org.elasticsearch.action.support.master.MasterNodeOperationRequest;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.unit.TimeValue;

import java.io.IOException;

import static org.elasticsearch.common.unit.TimeValue.*;

/**
 * @author kimchy (shay.banon)
 */
public class NodesShutdownRequest extends MasterNodeOperationRequest {

    String[] nodesIds = Strings.EMPTY_ARRAY;

    TimeValue delay = TimeValue.timeValueSeconds(1);

    NodesShutdownRequest() {
    }

    public NodesShutdownRequest(String... nodesIds) {
        this.nodesIds = nodesIds;
    }

    public NodesShutdownRequest nodesIds(String... nodesIds) {
        this.nodesIds = nodesIds;
        return this;
    }

    /**
     * The delay for the shutdown to occur. Defaults to <tt>1s</tt>.
     */
    public NodesShutdownRequest delay(TimeValue delay) {
        this.delay = delay;
        return this;
    }

    public TimeValue delay() {
        return this.delay;
    }

    /**
     * The delay for the shutdown to occur. Defaults to <tt>1s</tt>.
     */
    public NodesShutdownRequest delay(String delay) {
        return delay(TimeValue.parseTimeValue(delay, null));
    }

    @Override public ActionRequestValidationException validate() {
        return null;
    }

    @Override public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        delay = readTimeValue(in);
        int size = in.readVInt();
        if (size > 0) {
            nodesIds = new String[size];
            for (int i = 0; i < nodesIds.length; i++) {
                nodesIds[i] = in.readUTF();
            }
        }
    }

    @Override public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        delay.writeTo(out);
        if (nodesIds == null) {
            out.writeVInt(0);
        } else {
            out.writeVInt(nodesIds.length);
            for (String nodeId : nodesIds) {
                out.writeUTF(nodeId);
            }
        }
    }
}
