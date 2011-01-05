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

package org.elasticsearch.indices;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;

import java.io.IOException;
import java.io.Serializable;

/**
 * Global information on indices stats running on a specific node.
 *
 * @author kimchy (shay.banon)
 */
public class IndicesStats implements Streamable, Serializable, ToXContent {

    private ByteSizeValue storeSize;

    private ByteSizeValue fieldCacheSize;

    private ByteSizeValue filterCacheSize;

    private long fieldCacheEvictions;

    IndicesStats() {
    }

    public IndicesStats(ByteSizeValue storeSize, ByteSizeValue fieldCacheSize, ByteSizeValue filterCacheSize,
                        long fieldCacheEvictions) {
        this.storeSize = storeSize;
        this.fieldCacheSize = fieldCacheSize;
        this.filterCacheSize = filterCacheSize;
        this.fieldCacheEvictions = fieldCacheEvictions;
    }

    /**
     * The size of the index storage taken on the node.
     */
    public ByteSizeValue storeSize() {
        return this.storeSize;
    }

    /**
     * The size of the index storage taken on the node.
     */
    public ByteSizeValue getStoreSize() {
        return storeSize;
    }

    public ByteSizeValue fieldCacheSize() {
        return this.fieldCacheSize;
    }

    public ByteSizeValue getFieldCacheSize() {
        return this.fieldCacheSize;
    }

    public ByteSizeValue filterCacheSize() {
        return this.filterCacheSize;
    }

    public ByteSizeValue getFilterCacheSize() {
        return this.filterCacheSize;
    }

    public long fieldCacheEvictions() {
        return this.fieldCacheEvictions;
    }

    public long getFieldCacheEvictions() {
        return fieldCacheEvictions();
    }

    public static IndicesStats readIndicesStats(StreamInput in) throws IOException {
        IndicesStats stats = new IndicesStats();
        stats.readFrom(in);
        return stats;
    }

    @Override public void readFrom(StreamInput in) throws IOException {
        storeSize = ByteSizeValue.readBytesSizeValue(in);
        fieldCacheSize = ByteSizeValue.readBytesSizeValue(in);
        filterCacheSize = ByteSizeValue.readBytesSizeValue(in);
    }

    @Override public void writeTo(StreamOutput out) throws IOException {
        storeSize.writeTo(out);
        fieldCacheSize.writeTo(out);
        filterCacheSize.writeTo(out);
    }

    @Override public void toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(Fields.INDICES);
        builder.field(Fields.STORE_SIZE, storeSize.toString());
        builder.field(Fields.STORE_SIZE_IN_BYTES, storeSize.bytes());
        builder.field(Fields.FIELD_CACHE_EVICTIONS, fieldCacheEvictions);
        builder.field(Fields.FIELD_CACHE_SIZE, fieldCacheSize.toString());
        builder.field(Fields.FIELD_CACHE_SIZE_IN_BYTES, fieldCacheSize.bytes());
        builder.field(Fields.FILTER_CACHE_SIZE, filterCacheSize.toString());
        builder.field(Fields.FILTER_CACHE_SIZE_IN_BYTES, filterCacheSize.bytes());
        builder.endObject();
    }

    static final class Fields {
        static final XContentBuilderString INDICES = new XContentBuilderString("indices");
        static final XContentBuilderString STORE_SIZE = new XContentBuilderString("store_size");
        static final XContentBuilderString STORE_SIZE_IN_BYTES = new XContentBuilderString("store_size_in_bytes");
        static final XContentBuilderString FIELD_CACHE_SIZE = new XContentBuilderString("field_cache_size");
        static final XContentBuilderString FIELD_CACHE_SIZE_IN_BYTES = new XContentBuilderString("field_cache_size_in_bytes");
        static final XContentBuilderString FIELD_CACHE_EVICTIONS = new XContentBuilderString("field_cache_evictions");
        static final XContentBuilderString FILTER_CACHE_SIZE = new XContentBuilderString("filter_cache_size");
        static final XContentBuilderString FILTER_CACHE_SIZE_IN_BYTES = new XContentBuilderString("filter_cache_size_in_bytes");
    }
}
