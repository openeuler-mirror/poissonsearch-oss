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

package org.elasticsearch.index.field.longs;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.FieldCache;
import org.elasticsearch.index.field.FieldData;
import org.elasticsearch.index.field.FieldDataOptions;
import org.elasticsearch.index.field.support.FieldDataLoader;
import org.elasticsearch.util.gnu.trove.TLongArrayList;

import java.io.IOException;

/**
 * @author kimchy (Shay Banon)
 */
public abstract class LongFieldData extends FieldData {

    static final long[] EMPTY_LONG_ARRAY = new long[0];


    protected final long[] values;
    protected final int[] freqs;

    protected LongFieldData(String fieldName, FieldDataOptions options, long[] values, int[] freqs) {
        super(fieldName, options);
        this.values = values;
        this.freqs = freqs;
    }

    abstract public long value(int docId);

    abstract public long[] values(int docId);

    @Override public Type type() {
        return Type.LONG;
    }

    public void forEachValue(ValueProc proc) {
        if (freqs == null) {
            for (int i = 1; i < values.length; i++) {
                proc.onValue(values[i], -1);
            }
        } else {
            for (int i = 1; i < values.length; i++) {
                proc.onValue(values[i], freqs[i]);
            }
        }
    }

    public static interface ValueProc {
        void onValue(long value, int freq);
    }


    public static LongFieldData load(IndexReader reader, String field, FieldDataOptions options) throws IOException {
        return FieldDataLoader.load(reader, field, options, new LongTypeLoader());
    }

    static class LongTypeLoader extends FieldDataLoader.FreqsTypeLoader<LongFieldData> {

        private final TLongArrayList terms = new TLongArrayList();

        LongTypeLoader() {
            super();
            // the first one indicates null value
            terms.add(0);
        }

        @Override public void collectTerm(String term) {
            terms.add(FieldCache.NUMERIC_UTILS_LONG_PARSER.parseLong(term));
        }

        @Override public LongFieldData buildSingleValue(String field, int[] order) {
            return new SingleValueLongFieldData(field, options, order, terms.toNativeArray(), buildFreqs());
        }

        @Override public LongFieldData buildMultiValue(String field, int[][] order) {
            return new MultiValueLongFieldData(field, options, order, terms.toNativeArray(), buildFreqs());
        }
    }
}