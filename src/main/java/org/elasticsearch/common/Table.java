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

package org.elasticsearch.common;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import org.elasticsearch.ElasticSearchIllegalArgumentException;

import java.util.*;

/**
 */
public class Table {

    protected List<Cell> headers = new ArrayList<Cell>();
    protected List<List<Cell>> rows = new ArrayList<List<Cell>>();

    protected Map<String,List<Cell>> map = Maps.newHashMap();

    protected List<Cell> currentCells;

    protected boolean inHeaders = false;

    public Table startHeaders() {
        inHeaders = true;
        currentCells = new ArrayList<Cell>();
        return this;
    }

    public Table endHeaders() {
        inHeaders = false;
        headers = currentCells;
        currentCells = null;

        /* Create associative structure for columns that
         * contain the same cells as the rows:
         *
         *     header1 => [Cell, Cell, ...]
         *     header2 => [Cell, Cell, ...]
         *     header3 => [Cell, Cell, ...]
         */
        for (Cell header : headers) {
            map.put((String) header.value, new ArrayList<Cell>());
        }

        return this;
    }

    public Table startRow() {
        if (headers.isEmpty()) {
            throw new ElasticSearchIllegalArgumentException("no headers added...");
        }
        currentCells = new ArrayList<Cell>(headers.size());
        return this;
    }

    public Table endRow(boolean check) {
        if (check && (currentCells.size() != headers.size())) {
            throw new ElasticSearchIllegalArgumentException("mismatch on number of cells in a row compared to header");
        }
        rows.add(currentCells);
        currentCells = null;
        return this;
    }

    public Table endRow() {
        endRow(true);
        return this;
    }

    public Table addCell(Cell cell) {
        currentCells.add(cell);

        // If we're in a value row, also populate the named column.
        if (!inHeaders) {
            String hdr = (String) headers.get(currentCells.indexOf(cell)).value;
            map.get(hdr).add(cell);
        }

        return this;
    }

    public Table addCell(Object value) {
        return addCell(value, "");
    }

    public Table addCell(Object value, String attributes) {
        if (!inHeaders) {
            if (currentCells.size() == headers.size()) {
                throw new ElasticSearchIllegalArgumentException("can't add more cells to a row than the header");
            }
        }
        Map<String, String> mAttr;
        if (attributes.length() == 0) {
            if (inHeaders) {
                mAttr = ImmutableMap.of();
            } else {
                // get the attributes of the header cell we are going to add to
                mAttr = headers.get(currentCells.size()).attr;
            }
        } else {
            mAttr = new HashMap<String, String>();
            if (!inHeaders) {
                // get the attributes of the header cell we are going to add
                mAttr.putAll(headers.get(currentCells.size()).attr);
            }
            String[] sAttrs = Strings.splitStringToArray(attributes, ';');
            for (String sAttr : sAttrs) {
                if (sAttr.length() == 0) {
                    continue;
                }
                int idx = sAttr.indexOf(':');
                mAttr.put(sAttr.substring(0, idx), sAttr.substring(idx + 1));
            }
        }
        addCell(new Cell(value, mAttr));
        return this;
    }

    public List<Cell> getHeaders() {
        return this.headers;
    }

    public Iterable<List<Cell>> rowIterator() { return rows; }

    public List<List<Cell>> getRows() {
        return rows;
    }

    public List<Cell>[] getRowsAsArray() {
        return (List<Cell>[]) rows.toArray();
    }

    public Map<String, List<Cell>> getAsMap() { return this.map; }

    public List<Cell> getHeadersFromNames(List<String> headerNames) {
        List<Cell> hdrs = new ArrayList<Cell>();
        for (String hdrToFind : headerNames) {
            for (Cell header : headers) {
                if (((String) header.value).equalsIgnoreCase(hdrToFind)) {
                    hdrs.add(header);
                }
            }
        }
        return hdrs;
    }

    public Table addTable(Table t2) {
        Table t1 = this;
        Table t = new Table();

        t.startHeaders();

        for (Cell c : t1.getHeaders()) {
            t.addCell(c);
        }

        for (Cell c : t2.getHeaders()) {
            t.addCell(c);
        }

        t.endHeaders();

        if (t1.rows.size() != t2.rows.size()) {
            StringBuilder sb = new StringBuilder();
            sb.append("cannot add a table with ");
            sb.append(t2.rows.size());
            sb.append(" rows to table with ");
            sb.append(t1.rows.size());
            sb.append(" rows");
            throw new ElasticSearchIllegalArgumentException(sb.toString());
        }

        for (int i = 0; i < t1.rows.size(); i++) {
            t.startRow();
            for (Cell c : t1.rows.get(i)) {
                t.addCell(c);
            }
            for (Cell c : t2.rows.get(i)) {
                t.addCell(c);
            }
            t.endRow(false);
        }

        return t;
    }

    public Table addColumn(String headerName, String attrs, List<Object> values) {
        Table t = new Table();
        t.startHeaders().addCell(headerName, attrs).endHeaders();
        for (Object val : values) {
            t.startRow().addCell(val).endRow();
        }
        return this.addTable(t);
    }

    public static class Cell {
        public final Object value;
        public final Map<String, String> attr;

        public Cell(Object value) {
            this.value = value;
            this.attr = new HashMap<String, String>();
        }

        public Cell(Object value, Map<String, String> attr) {
            this.value = value;
            this.attr = attr;
        }
    }
}
