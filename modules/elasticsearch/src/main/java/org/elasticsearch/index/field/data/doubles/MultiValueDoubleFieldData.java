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

package org.elasticsearch.index.field.data.doubles;

import org.elasticsearch.common.RamUsage;
import org.elasticsearch.common.thread.ThreadLocals;

/**
 * @author kimchy (shay.banon)
 */
public class MultiValueDoubleFieldData extends DoubleFieldData {

    private static final int VALUE_CACHE_SIZE = 10;

    private ThreadLocal<ThreadLocals.CleanableValue<double[][]>> valuesCache = new ThreadLocal<ThreadLocals.CleanableValue<double[][]>>() {
        @Override protected ThreadLocals.CleanableValue<double[][]> initialValue() {
            double[][] value = new double[VALUE_CACHE_SIZE][];
            for (int i = 0; i < value.length; i++) {
                value[i] = new double[i];
            }
            return new ThreadLocals.CleanableValue<double[][]>(value);
        }
    };

    // order with value 0 indicates no value
    private final int[][] ordinals;

    public MultiValueDoubleFieldData(String fieldName, int[][] ordinals, double[] values) {
        super(fieldName, values);
        this.ordinals = ordinals;
    }

    @Override protected long computeSizeInBytes() {
        long size = super.computeSizeInBytes();
        size += RamUsage.NUM_BYTES_ARRAY_HEADER; // for the top level array
        for (int[] ordinal : ordinals) {
            size += RamUsage.NUM_BYTES_INT * ordinal.length + RamUsage.NUM_BYTES_ARRAY_HEADER;
        }
        return size;
    }

    @Override public boolean multiValued() {
        return true;
    }

    @Override public boolean hasValue(int docId) {
        for (int[] ordinal : ordinals) {
            if (ordinal[docId] != 0) {
                return true;
            }
        }
        return false;
    }

    @Override public void forEachValueInDoc(int docId, StringValueInDocProc proc) {
        boolean found = false;
        for (int[] ordinal : ordinals) {
            int loc = ordinal[docId];
            if (loc != 0) {
                found = true;
                proc.onValue(docId, Double.toString(values[loc]));
            }
        }
        if (!found) {
            proc.onMissing(docId);
        }
    }

    @Override public void forEachValueInDoc(int docId, DoubleValueInDocProc proc) {
        for (int[] ordinal : ordinals) {
            int loc = ordinal[docId];
            if (loc != 0) {
                proc.onValue(docId, values[loc]);
            }
        }
    }

    @Override public void forEachValueInDoc(int docId, LongValueInDocProc proc) {
        for (int[] ordinal : ordinals) {
            int loc = ordinal[docId];
            if (loc != 0) {
                proc.onValue(docId, (long) values[loc]);
            }
        }
    }

    @Override public void forEachValueInDoc(int docId, MissingDoubleValueInDocProc proc) {
        boolean found = false;
        for (int[] ordinal : ordinals) {
            int loc = ordinal[docId];
            if (loc != 0) {
                found = true;
                proc.onValue(docId, values[loc]);
            }
        }
        if (!found) {
            proc.onMissing(docId);
        }
    }

    @Override public void forEachValueInDoc(int docId, MissingLongValueInDocProc proc) {
        boolean found = false;
        for (int[] ordinal : ordinals) {
            int loc = ordinal[docId];
            if (loc != 0) {
                found = true;
                proc.onValue(docId, (long) values[loc]);
            }
        }
        if (!found) {
            proc.onMissing(docId);
        }
    }

    @Override public void forEachValueInDoc(int docId, ValueInDocProc proc) {
        boolean found = false;
        for (int[] ordinal : ordinals) {
            int loc = ordinal[docId];
            if (loc != 0) {
                found = true;
                proc.onValue(docId, values[loc]);
            }
        }
        if (!found) {
            proc.onMissing(docId);
        }
    }

    @Override public void forEachOrdinalInDoc(int docId, OrdinalInDocProc proc) {
        boolean found = false;
        for (int[] ordinal : ordinals) {
            int loc = ordinal[docId];
            if (loc != 0) {
                found = true;
                proc.onOrdinal(docId, loc);
            }
        }
        if (!found) {
            proc.onOrdinal(docId, 0);
        }
    }

    @Override public double[] doubleValues(int docId) {
        return values(docId);
    }

    @Override public double value(int docId) {
        for (int[] ordinal : ordinals) {
            int loc = ordinal[docId];
            if (loc != 0) {
                return values[loc];
            }
        }
        return 0;
    }

    @Override public double[] values(int docId) {
        int length = 0;
        for (int[] ordinal : ordinals) {
            if (ordinal[docId] != 0) {
                length++;
            }
        }
        if (length == 0) {
            return EMPTY_DOUBLE_ARRAY;
        }
        double[] doubles;
        if (length < VALUE_CACHE_SIZE) {
            doubles = valuesCache.get().get()[length];
        } else {
            doubles = new double[length];
        }
        int i = 0;
        for (int[] ordinal : ordinals) {
            int loc = ordinal[docId];
            if (loc != 0) {
                doubles[i++] = values[loc];
            }
        }
        return doubles;
    }
}