/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
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

package org.elasticsearch.index.fielddata;

import org.apache.lucene.util.BytesRef;
import org.elasticsearch.index.fielddata.util.*;
import org.elasticsearch.index.mapper.geo.GeoPoint;

/**
 * Script level doc values, the assumption is that any implementation will implement a <code>getValue</code>
 * and a <code>getValues</code> that return the relevant type that then can be used in scripts.
 */
public interface ScriptDocValues {

    void setNextDocId(int docId);

    boolean isEmpty();

    static class Strings implements ScriptDocValues {

        private final StringValues values;
        private int docId;

        public Strings(StringValues values) {
            this.values = values;
        }

        @Override
        public void setNextDocId(int docId) {
            this.docId = docId;
        }

        @Override
        public boolean isEmpty() {
            return !values.hasValue(docId);
        }

        public String getValue() {
            return values.getValue(docId);
        }

        public StringArrayRef getValues() {
            return values.getValues(docId);
        }
    }

    static class Bytes implements ScriptDocValues {

        private final BytesValues values;
        private int docId;

        public Bytes(BytesValues values) {
            this.values = values;
        }

        @Override
        public void setNextDocId(int docId) {
            this.docId = docId;
        }

        @Override
        public boolean isEmpty() {
            return !values.hasValue(docId);
        }

        public BytesRef getValue() {
            return values.getValue(docId);
        }

        public BytesRefArrayRef getValues() {
            return values.getValues(docId);
        }
    }

    static class NumericByte implements ScriptDocValues {

        private final ByteValues values;
        private int docId;

        public NumericByte(ByteValues values) {
            this.values = values;
        }

        @Override
        public void setNextDocId(int docId) {
            this.docId = docId;
        }

        @Override
        public boolean isEmpty() {
            return !values.hasValue(docId);
        }

        public byte getValue() {
            return values.getValue(docId);
        }

        public ByteArrayRef getValues() {
            return values.getValues(docId);
        }
    }

    static class NumericShort implements ScriptDocValues {

        private final ShortValues values;
        private int docId;

        public NumericShort(ShortValues values) {
            this.values = values;
        }

        @Override
        public void setNextDocId(int docId) {
            this.docId = docId;
        }

        @Override
        public boolean isEmpty() {
            return !values.hasValue(docId);
        }

        public short getValue() {
            return values.getValue(docId);
        }

        public ShortArrayRef getValues() {
            return values.getValues(docId);
        }
    }

    static class NumericInteger implements ScriptDocValues {

        private final IntValues values;
        private int docId;

        public NumericInteger(IntValues values) {
            this.values = values;
        }

        @Override
        public void setNextDocId(int docId) {
            this.docId = docId;
        }

        @Override
        public boolean isEmpty() {
            return !values.hasValue(docId);
        }

        public int getValue() {
            return values.getValue(docId);
        }

        public IntArrayRef getValues() {
            return values.getValues(docId);
        }
    }

    static class NumericLong implements ScriptDocValues {

        private final LongValues values;
        private int docId;

        public NumericLong(LongValues values) {
            this.values = values;
        }

        @Override
        public void setNextDocId(int docId) {
            this.docId = docId;
        }

        @Override
        public boolean isEmpty() {
            return !values.hasValue(docId);
        }

        public long getValue() {
            return values.getValue(docId);
        }

        public LongArrayRef getValues() {
            return values.getValues(docId);
        }
    }

    static class NumericFloat implements ScriptDocValues {

        private final FloatValues values;
        private int docId;

        public NumericFloat(FloatValues values) {
            this.values = values;
        }

        @Override
        public void setNextDocId(int docId) {
            this.docId = docId;
        }

        @Override
        public boolean isEmpty() {
            return !values.hasValue(docId);
        }

        public float getValue() {
            return values.getValue(docId);
        }

        public FloatArrayRef getValues() {
            return values.getValues(docId);
        }
    }

    static class NumericDouble implements ScriptDocValues {

        private final DoubleValues values;
        private int docId;

        public NumericDouble(DoubleValues values) {
            this.values = values;
        }

        @Override
        public void setNextDocId(int docId) {
            this.docId = docId;
        }

        @Override
        public boolean isEmpty() {
            return !values.hasValue(docId);
        }

        public double getValue() {
            return values.getValue(docId);
        }

        public DoubleArrayRef getValues() {
            return values.getValues(docId);
        }
    }

    static class GeoPoints implements ScriptDocValues {

        private final GeoPointValues values;
        private int docId;

        public GeoPoints(GeoPointValues values) {
            this.values = values;
        }

        @Override
        public void setNextDocId(int docId) {
            this.docId = docId;
        }

        @Override
        public boolean isEmpty() {
            return !values.hasValue(docId);
        }

        public GeoPoint getValue() {
            return values.getValue(docId);
        }

        public GeoPointArrayRef getValues() {
            return values.getValues(docId);
        }
    }
}
