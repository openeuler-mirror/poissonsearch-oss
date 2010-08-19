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

package org.elasticsearch.action.get;

import org.elasticsearch.ElasticSearchParseException;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.common.Unicode;
import org.elasticsearch.common.collect.ImmutableMap;
import org.elasticsearch.common.compress.lzf.LZFDecoder;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.builder.XContentBuilder;
import org.elasticsearch.rest.action.support.RestXContentBuilder;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

import static org.elasticsearch.action.get.GetField.*;
import static org.elasticsearch.common.collect.Iterators.*;
import static org.elasticsearch.common.collect.Maps.*;

/**
 * The response of a get action.
 *
 * @author kimchy (shay.banon)
 * @see GetRequest
 * @see org.elasticsearch.client.Client#get(GetRequest)
 */
public class GetResponse implements ActionResponse, Streamable, Iterable<GetField>, ToXContent {

    private String index;

    private String type;

    private String id;

    private boolean exists;

    private Map<String, GetField> fields;

    private Map<String, Object> sourceAsMap;

    private byte[] source;

    GetResponse() {
    }

    GetResponse(String index, String type, String id, boolean exists, byte[] source, Map<String, GetField> fields) {
        this.index = index;
        this.type = type;
        this.id = id;
        this.exists = exists;
        this.source = source;
        this.fields = fields;
        if (this.fields == null) {
            this.fields = ImmutableMap.of();
        }
    }

    /**
     * Does the document exists.
     */
    public boolean exists() {
        return exists;
    }

    /**
     * Does the document exists.
     */
    public boolean isExists() {
        return exists;
    }

    /**
     * The index the document was fetched from.
     */
    public String index() {
        return this.index;
    }

    /**
     * The index the document was fetched from.
     */
    public String getIndex() {
        return index;
    }

    /**
     * The type of the document.
     */
    public String type() {
        return type;
    }

    /**
     * The type of the document.
     */
    public String getType() {
        return type;
    }

    /**
     * The id of the document.
     */
    public String id() {
        return id;
    }

    /**
     * The id of the document.
     */
    public String getId() {
        return id;
    }

    /**
     * The source of the document if exists.
     */
    public byte[] source() {
        if (source == null) {
            return null;
        }
        if (LZFDecoder.isCompressed(source)) {
            try {
                this.source = LZFDecoder.decode(source);
            } catch (IOException e) {
                throw new ElasticSearchParseException("failed to decompress source", e);
            }
        }
        return this.source;
    }

    /**
     * Is the source empty (not available) or not.
     */
    public boolean isSourceEmpty() {
        return source == null;
    }

    /**
     * The source of the document (as a string).
     */
    public String sourceAsString() {
        if (source == null) {
            return null;
        }
        return Unicode.fromBytes(source());
    }

    /**
     * The source of the document (As a map).
     */
    @SuppressWarnings({"unchecked"})
    public Map<String, Object> sourceAsMap() throws ElasticSearchParseException {
        if (source == null) {
            return null;
        }
        if (sourceAsMap != null) {
            return sourceAsMap;
        }
        byte[] source = source();
        XContentParser parser = null;
        try {
            parser = XContentFactory.xContent(source).createParser(source);
            sourceAsMap = parser.map();
            parser.close();
            return sourceAsMap;
        } catch (Exception e) {
            throw new ElasticSearchParseException("Failed to parse source to map", e);
        } finally {
            if (parser != null) {
                parser.close();
            }
        }
    }

    public Map<String, Object> getSource() {
        return sourceAsMap();
    }

    public Map<String, GetField> fields() {
        return this.fields;
    }

    public Map<String, GetField> getFields() {
        return fields;
    }

    public GetField field(String name) {
        return fields.get(name);
    }

    @Override public Iterator<GetField> iterator() {
        if (fields == null) {
            return emptyIterator();
        }
        return fields.values().iterator();
    }

    @Override public void toXContent(XContentBuilder builder, Params params) throws IOException {
        if (!exists()) {
            builder.startObject();
            builder.field("_index", index);
            builder.field("_type", type);
            builder.field("_id", id);
            builder.endObject();
        } else {
            builder.startObject();
            builder.field("_index", index);
            builder.field("_type", type);
            builder.field("_id", id);
            if (source != null) {
                RestXContentBuilder.restDocumentSource(source, builder, params);
            }

            if (fields != null && !fields.isEmpty()) {
                builder.startObject("fields");
                for (GetField field : fields.values()) {
                    if (field.values().isEmpty()) {
                        continue;
                    }
                    if (field.values().size() == 1) {
                        builder.field(field.name(), field.values().get(0));
                    } else {
                        builder.field(field.name());
                        builder.startArray();
                        for (Object value : field.values()) {
                            builder.value(value);
                        }
                        builder.endArray();
                    }
                }
                builder.endObject();
            }


            builder.endObject();
        }
    }

    @Override public void readFrom(StreamInput in) throws IOException {
        index = in.readUTF();
        type = in.readUTF();
        id = in.readUTF();
        exists = in.readBoolean();
        if (exists) {
            int size = in.readVInt();
            if (size > 0) {
                source = new byte[size];
                in.readFully(source);
            }
            size = in.readVInt();
            if (size == 0) {
                fields = ImmutableMap.of();
            } else {
                fields = newHashMapWithExpectedSize(size);
                for (int i = 0; i < size; i++) {
                    GetField field = readGetField(in);
                    fields.put(field.name(), field);
                }
            }
        }
    }

    @Override public void writeTo(StreamOutput out) throws IOException {
        out.writeUTF(index);
        out.writeUTF(type);
        out.writeUTF(id);
        out.writeBoolean(exists);
        if (exists) {
            if (source == null) {
                out.writeVInt(0);
            } else {
                out.writeVInt(source.length);
                out.writeBytes(source);
            }
            if (fields == null) {
                out.writeVInt(0);
            } else {
                out.writeVInt(fields.size());
                for (GetField field : fields.values()) {
                    field.writeTo(out);
                }
            }
        }
    }
}
