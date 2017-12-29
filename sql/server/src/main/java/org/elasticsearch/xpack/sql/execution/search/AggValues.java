/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.execution.search;

import java.util.Arrays;
import java.util.List;

/**
 * Aggregations are returned by Elasticsearch in a tree structure where each
 * nested level can have a different size. For example a group by a, b, c
 * results in 3-level nested array where each level contains all the relevant
 * values for its parent entry.
 * <p>
 * Assuming there's a total of 2 A's, 3 B's and 5 C's, the values will be
 * <ul>
 * <li>A-agg level = { A1, A2 }
 * <li>B-agg level = { { A1B1, A1B2, A1B3 }, { A2B1, A2B2, A2B3 }
 * <li>C-agg level = { { { A1B1C1, A1B1C2 ..}, { A1B2C1, etc... } } } and so on
 * </ul>
 * <p>
 * Further more the columns are added in the order in which they are requested
 * (0, 1, 2) eliminating the need for keys as these are implicit (their position
 * in the list).
 * <p>
 * To help with the iteration, there are two dedicated counters:
 * one that carries (increments) the counter for each level (indicated by the
 * position inside the array) once the children reach their max a flat cursor
 * to indicate the row.
 */
class AggValues {
    private int row = 0;

    private final List<Object[]> columns;
    private int[] indexPerLevel;
    private int size;

    AggValues(List<Object[]> columns) {
        this.columns = columns;
    }

    void init(int maxDepth, int limit) {
        int sz = computeSize(columns, maxDepth);
        size = limit > 0 ? Math.min(limit, sz) : sz;
        indexPerLevel = new int[maxDepth + 1];
    }

    private static int computeSize(List<Object[]> columns, int maxDepth) {
        // look only at arrays with the right depth (the others might be
        // counters or other functions)
        // then return the parent array to compute the actual returned results
        Object[] leafArray = null;
        for (int i = 0; i < columns.size() && leafArray == null; i++) {
            Object[] col = columns.get(i);
            Object o = col;
            int level = 0;
            Object[] parent = null;
            // keep unwrapping until the desired level is reached
            while (o instanceof Object[]) {
                col = ((Object[]) o);
                if (col.length > 0) {
                    if (level == maxDepth) {
                        leafArray = parent;
                        break;
                    } else {
                        parent = col;
                        level++;
                        o = col[0];
                    }
                } else {
                    o = null;
                }
            }
        }

        if (leafArray == null) {
            return columns.get(0).length;
        }

        int sz = 0;
        for (Object leaf : leafArray) {
            sz += ((Object[]) leaf).length;
        }
        return sz;
    }

    Object column(int column) {
        Object o = columns.get(column);

        for (int lvl = 0; o instanceof Object[]; lvl++) {
            Object[] arr = (Object[]) o;
            // the current branch is done
            if (indexPerLevel[lvl] == arr.length) {
                // reset the current branch
                indexPerLevel[lvl] = 0;
                // bump the parent - if it's too big it, the loop will restart
                // again from that position
                indexPerLevel[lvl - 1]++;
                // restart the loop
                lvl = -1;
                o = columns.get(column);
            } else {
                o = arr[indexPerLevel[lvl]];
            }
        }
        return o;
    }

    int size() {
        return size;
    }

    void reset() {
        row = 0;
        Arrays.fill(indexPerLevel, 0);
    }

    boolean nextRow() {
        if (row < size - 1) {
            row++;
            // increment leaf counter - the size check is done lazily while retrieving the columns
            indexPerLevel[indexPerLevel.length - 1]++;
            return true;
        }
        return false;
    }

    boolean hasCurrentRow() {
        return row < size;
    }
}
