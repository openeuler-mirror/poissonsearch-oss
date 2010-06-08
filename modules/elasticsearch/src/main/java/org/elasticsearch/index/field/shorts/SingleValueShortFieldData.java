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

package org.elasticsearch.index.field.shorts;

import org.elasticsearch.index.field.FieldDataOptions;
import org.elasticsearch.index.field.doubles.DoubleFieldData;

/**
 * @author kimchy (shay.banon)
 */
public class SingleValueShortFieldData extends ShortFieldData {

    private ThreadLocal<double[]> doublesValuesCache = new ThreadLocal<double[]>() {
        @Override protected double[] initialValue() {
            return new double[1];
        }
    };

    private ThreadLocal<short[]> valuesCache = new ThreadLocal<short[]>() {
        @Override protected short[] initialValue() {
            return new short[1];
        }
    };

    // order with value 0 indicates no value
    private final int[] order;

    public SingleValueShortFieldData(String fieldName, FieldDataOptions options, int[] order, short[] values, int[] freqs) {
        super(fieldName, options, values, freqs);
        this.order = order;
    }

    @Override public boolean multiValued() {
        return false;
    }

    @Override public boolean hasValue(int docId) {
        return order[docId] != 0;
    }

    @Override public void forEachValueInDoc(int docId, StringValueInDocProc proc) {
        int loc = order[docId];
        if (loc == 0) {
            return;
        }
        proc.onValue(docId, Short.toString(values[loc]));
    }

    @Override public void forEachValueInDoc(int docId, DoubleValueInDocProc proc) {
        int loc = order[docId];
        if (loc == 0) {
            return;
        }
        proc.onValue(docId, values[loc]);
    }

    @Override public short value(int docId) {
        return values[order[docId]];
    }

    @Override public double[] doubleValues(int docId) {
        int loc = order[docId];
        if (loc == 0) {
            return DoubleFieldData.EMPTY_DOUBLE_ARRAY;
        }
        double[] ret = doublesValuesCache.get();
        ret[0] = values[loc];
        return ret;
    }

    @Override public short[] values(int docId) {
        int loc = order[docId];
        if (loc == 0) {
            return EMPTY_SHORT_ARRAY;
        }
        short[] ret = valuesCache.get();
        ret[0] = values[loc];
        return ret;
    }
}