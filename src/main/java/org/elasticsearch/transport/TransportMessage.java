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

package org.elasticsearch.transport;

import org.elasticsearch.common.ContextAndHeaderHolder;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.transport.TransportAddress;

import java.io.IOException;
import java.util.HashMap;

/**
 * The transport message is also a {@link ContextAndHeaderHolder context holder} that holds <b>transient</b> context, that is,
 * the context is not serialized with message.
 */
public abstract class TransportMessage<TM extends TransportMessage<TM>> extends ContextAndHeaderHolder<TM> implements Streamable {

    private TransportAddress remoteAddress;

    protected TransportMessage() {
    }

    protected TransportMessage(TM message) {
        // create a new copy of the headers/context, since we are creating a new request
        // which might have its headers/context changed in the context of that specific request

        if (message.headers != null) {
            this.headers = new HashMap<>(message.headers);
        }
        copyContextFrom(message);
    }

    public void remoteAddress(TransportAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public TransportAddress remoteAddress() {
        return remoteAddress;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        headers = in.readBoolean() ? in.readMap() : null;
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        if (headers == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeMap(headers);
        }
    }
}
