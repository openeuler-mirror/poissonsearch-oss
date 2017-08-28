/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.session;

import org.elasticsearch.xpack.sql.type.Schema;

import java.util.List;

class ListRowSetCursor extends AbstractRowSetCursor {

    private final List<List<?>> list;
    private int pos = 0;

    ListRowSetCursor(Schema schema, List<List<?>> list) {
        super(schema, null);
        this.list = list;
    }

    @Override
    protected boolean doHasCurrent() {
        return pos < list.size();
    }

    @Override
    protected boolean doNext() {
        if (pos + 1 < list.size()) {
            pos++;
            return true;
        }
        return false;
    }

    @Override
    protected Object getColumn(int index) {
        return list.get(pos).get(index);
    }

    @Override
    protected void doReset() {
        pos = 0;
    }

    @Override
    public int size() {
        return list.size();
    }

    @Override
    public Cursor nextPageCursor() {
        return Cursor.EMPTY;
    }
}
