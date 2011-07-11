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

package org.elasticsearch.transport;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;

public class TransportStats implements Streamable, ToXContent {

    private long serverOpen;

    TransportStats() {

    }

    public TransportStats(long serverOpen) {
        this.serverOpen = serverOpen;
    }

    public long serverOpen() {
        return this.serverOpen;
    }

    public long getServerOpen() {
        return serverOpen();
    }

    public static TransportStats readTransportStats(StreamInput in) throws IOException {
        TransportStats stats = new TransportStats();
        stats.readFrom(in);
        return stats;
    }

    @Override public void readFrom(StreamInput in) throws IOException {
        serverOpen = in.readVLong();
    }

    @Override public void writeTo(StreamOutput out) throws IOException {
        out.writeVLong(serverOpen);
    }

    @Override public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject("transport");
        builder.field("server_open", serverOpen);
        builder.endObject();
        return builder;
    }
}