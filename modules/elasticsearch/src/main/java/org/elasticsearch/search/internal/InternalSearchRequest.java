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

package org.elasticsearch.search.internal;

import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.util.Bytes;
import org.elasticsearch.util.Strings;
import org.elasticsearch.util.TimeValue;
import org.elasticsearch.util.io.stream.StreamInput;
import org.elasticsearch.util.io.stream.StreamOutput;
import org.elasticsearch.util.io.stream.Streamable;

import java.io.IOException;

import static org.elasticsearch.search.Scroll.*;
import static org.elasticsearch.util.TimeValue.*;

/**
 * Source structure:
 * <p/>
 * <pre>
 * {
 *  from : 0, size : 20, (optional, can be set on the request)
 *  sort : { "name.first" : {}, "name.last" : { reverse : true } }
 *  fields : [ "name.first", "name.last" ]
 *  queryParserName : "",
 *  query : { ... }
 *  facets : {
 *      "facet1" : {
 *          query : { ... }
 *      }
 *  }
 * }
 * </pre>
 *
 * @author kimchy (Shay Banon)
 */
public class InternalSearchRequest implements Streamable {

    private String index;

    private int shardId;

    private Scroll scroll;

    private TimeValue timeout;

    private String[] types = Strings.EMPTY_ARRAY;

    private byte[] source;

    private byte[] extraSource;

    public InternalSearchRequest() {
    }

    public InternalSearchRequest(ShardRouting shardRouting, byte[] source) {
        this(shardRouting.index(), shardRouting.id(), source);
    }

    public InternalSearchRequest(String index, int shardId, byte[] source) {
        this.index = index;
        this.shardId = shardId;
        this.source = source;
    }

    public String index() {
        return index;
    }

    public int shardId() {
        return shardId;
    }

    public byte[] source() {
        return this.source;
    }

    public byte[] extraSource() {
        return this.extraSource;
    }

    public InternalSearchRequest extraSource(byte[] extraSource) {
        this.extraSource = extraSource;
        return this;
    }

    public Scroll scroll() {
        return scroll;
    }

    public InternalSearchRequest scroll(Scroll scroll) {
        this.scroll = scroll;
        return this;
    }

    public TimeValue timeout() {
        return timeout;
    }

    public InternalSearchRequest timeout(TimeValue timeout) {
        this.timeout = timeout;
        return this;
    }

    public String[] types() {
        return types;
    }

    public void types(String[] types) {
        this.types = types;
    }

    @Override public void readFrom(StreamInput in) throws IOException {
        index = in.readUTF();
        shardId = in.readVInt();
        if (in.readBoolean()) {
            scroll = readScroll(in);
        }
        if (in.readBoolean()) {
            timeout = readTimeValue(in);
        }
        int size = in.readVInt();
        if (size == 0) {
            source = Bytes.EMPTY_ARRAY;
        } else {
            source = new byte[size];
            in.readFully(source);
        }
        size = in.readVInt();
        if (size == 0) {
            extraSource = Bytes.EMPTY_ARRAY;
        } else {
            extraSource = new byte[size];
            in.readFully(extraSource);
        }
        int typesSize = in.readVInt();
        if (typesSize > 0) {
            types = new String[typesSize];
            for (int i = 0; i < typesSize; i++) {
                types[i] = in.readUTF();
            }
        }
    }

    @Override public void writeTo(StreamOutput out) throws IOException {
        out.writeUTF(index);
        out.writeVInt(shardId);
        if (scroll == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            scroll.writeTo(out);
        }
        if (timeout == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            timeout.writeTo(out);
        }
        if (source == null) {
            out.writeVInt(0);
        } else {
            out.writeVInt(source.length);
            out.writeBytes(source);
        }
        if (extraSource == null) {
            out.writeVInt(0);
        } else {
            out.writeVInt(extraSource.length);
            out.writeBytes(extraSource);
        }
        out.writeVInt(types.length);
        for (String type : types) {
            out.writeUTF(type);
        }
    }
}
