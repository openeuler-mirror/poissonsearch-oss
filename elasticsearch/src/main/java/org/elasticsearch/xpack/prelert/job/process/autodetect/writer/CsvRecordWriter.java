/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.autodetect.writer;

import java.io.IOException;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.supercsv.io.CsvListWriter;
import org.supercsv.prefs.CsvPreference;

/**
 * Write the records to the outputIndex stream as UTF 8 encoded CSV
 */
public class CsvRecordWriter implements RecordWriter {
    private final CsvListWriter writer;

    /**
     * Create the writer on the OutputStream <code>os</code>.
     * This object will never close <code>os</code>.
     */
    public CsvRecordWriter(OutputStream os) {
        writer = new CsvListWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), CsvPreference.STANDARD_PREFERENCE);
    }

    @Override
    public void writeRecord(String[] record) throws IOException {
        writer.write(record);
    }

    @Override
    public void writeRecord(List<String> record) throws IOException {
        writer.write(record);
    }

    @Override
    public void flush() throws IOException {
        writer.flush();
    }

}
