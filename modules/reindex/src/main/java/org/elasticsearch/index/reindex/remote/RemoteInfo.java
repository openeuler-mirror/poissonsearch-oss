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

package org.elasticsearch.index.reindex.remote;

import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;

import java.io.IOException;

import static java.util.Objects.requireNonNull;

public class RemoteInfo implements Writeable {
    private final String scheme;
    private final String host;
    private final int port;
    private final BytesReference query;
    private final String username;
    private final String password;

    public RemoteInfo(String scheme, String host, int port, BytesReference query, String username, String password) {
        this.scheme = requireNonNull(scheme, "[scheme] must be specified to reindex from a remote cluster");
        this.host = requireNonNull(host, "[host] must be specified to reindex from a remote cluster");
        this.port = port;
        this.query = requireNonNull(query, "[query] must be specified to reindex from a remote cluster");
        this.username = username;
        this.password = password;
    }

    /**
     * Read from a stream.
     */
    public RemoteInfo(StreamInput in) throws IOException {
        scheme = in.readString();
        host = in.readString();
        port = in.readVInt();
        query = in.readBytesReference();
        username = in.readOptionalString();
        password = in.readOptionalString();
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeString(scheme);
        out.writeString(host);
        out.writeVInt(port);
        out.writeBytesReference(query);
        out.writeOptionalString(username);
        out.writeOptionalString(password);
    }

    public String getScheme() {
        return scheme;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public BytesReference getQuery() {
        return query;
    }

    @Nullable
    public String getUsername() {
        return username;
    }

    @Nullable
    public String getPassword() {
        return password;
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder();
        if (false == "http".equals(scheme)) {
            // http is the default so it isn't worth taking up space if it is the scheme
            b.append("scheme=").append(scheme).append(' ');
        }
        b.append("host=").append(host).append(" port=").append(port).append(" query=").append(query.utf8ToString());
        if (username != null) {
            b.append(" username=").append(username);
        }
        if (password != null) {
            b.append(" password=<<>>");
        }
        return b.toString();
    }
}
