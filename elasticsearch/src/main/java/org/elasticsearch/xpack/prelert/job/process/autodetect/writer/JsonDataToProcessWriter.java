/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.autodetect.writer;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import org.elasticsearch.xpack.prelert.job.AnalysisConfig;
import org.elasticsearch.xpack.prelert.job.DataCounts;
import org.elasticsearch.xpack.prelert.job.DataDescription;
import org.elasticsearch.xpack.prelert.job.DataDescription.DataFormat;
import org.elasticsearch.xpack.prelert.job.SchedulerConfig;
import org.elasticsearch.xpack.prelert.job.process.autodetect.AutodetectProcess;
import org.elasticsearch.xpack.prelert.job.status.StatusReporter;
import org.elasticsearch.xpack.prelert.job.transform.TransformConfigs;

/**
 * A writer for transforming and piping JSON data from an
 * inputstream to outputstream.
 * The data written to outputIndex is length encoded each record
 * consists of number of fields followed by length/value pairs.
 * See CLengthEncodedInputParser.h in the C++ code for a more
 * detailed description.
 */
class JsonDataToProcessWriter extends AbstractDataToProcessWriter {
    private static final String ELASTICSEARCH_SOURCE_FIELD = "_source";

    /**
     * Scheduler config.  May be <code>null</code>.
     */
    private SchedulerConfig schedulerConfig;

    public JsonDataToProcessWriter(boolean includeControlField, AutodetectProcess autodetectProcess,
            DataDescription dataDescription, AnalysisConfig analysisConfig,
            SchedulerConfig schedulerConfig, TransformConfigs transforms,
            StatusReporter statusReporter, Logger logger) {
        super(includeControlField, autodetectProcess, dataDescription, analysisConfig, transforms,
                statusReporter, logger);
        this.schedulerConfig = schedulerConfig;
    }

    /**
     * Read the JSON inputIndex, transform to length encoded values and pipe to
     * the OutputStream. No transformation is applied to the data the timestamp
     * is expected in seconds from the epoch. If any of the fields in
     * <code>analysisFields</code> or the <code>DataDescription</code>s
     * timeField is missing from the JOSN inputIndex an exception is thrown
     */
    @Override
    public DataCounts write(InputStream inputStream) throws IOException {
        statusReporter.startNewIncrementalCount();

        try (JsonParser parser = new JsonFactory().createParser(inputStream)) {
            writeJson(parser);

            // this line can throw and will be propagated
            statusReporter.finishReporting();
        }

        return statusReporter.incrementalStats();
    }

    private void writeJson(JsonParser parser) throws IOException {
        Collection<String> analysisFields = inputFields();

        buildTransformsAndWriteHeader(analysisFields.toArray(new String[0]));

        int numFields = outputFieldCount();
        String[] input = new String[numFields];
        String[] record = new String[numFields];

        // We never expect to get the control field
        boolean[] gotFields = new boolean[analysisFields.size()];

        JsonRecordReader recordReader = makeRecordReader(parser);
        long inputFieldCount = recordReader.read(input, gotFields);
        while (inputFieldCount >= 0) {
            Arrays.fill(record, "");

            inputFieldCount = Math.max(inputFieldCount - 1, 0); // time field doesn't count

            long missing = missingFieldCount(gotFields);
            if (missing > 0) {
                statusReporter.reportMissingFields(missing);
            }

            for (InputOutputMap inOut : inputOutputMap) {
                String field = input[inOut.inputIndex];
                record[inOut.outputIndex] = (field == null) ? "" : field;
            }

            applyTransformsAndWrite(input, record, inputFieldCount);

            inputFieldCount = recordReader.read(input, gotFields);
        }
    }

    private String getRecordHoldingField() {
        if (dataDescription.getFormat().equals(DataFormat.ELASTICSEARCH)) {
            if (schedulerConfig != null) {
                if (schedulerConfig.getAggregationsOrAggs() != null) {
                    return SchedulerConfig.AGGREGATIONS.getPreferredName();
                }
            }
            return ELASTICSEARCH_SOURCE_FIELD;
        }
        return "";
    }

    // TODO norelease: Feels like this is checked in the wrong place. The fact that there is a different format, should
    // be specified to this class and this class shouldn't know about the existence of SchedulerConfig
    private JsonRecordReader makeRecordReader(JsonParser parser) {
        List<String> nestingOrder = (schedulerConfig != null) ?
                schedulerConfig.buildAggregatedFieldList() : Collections.emptyList();
                return nestingOrder.isEmpty() ? new SimpleJsonRecordReader(parser, inFieldIndexes, getRecordHoldingField(), logger)
                        : new AggregatedJsonRecordReader(parser, inFieldIndexes, getRecordHoldingField(), logger, nestingOrder);
    }

    /**
     * Don't enforce the check that all the fields are present in JSON docs.
     * Always returns true
     */
    @Override
    protected boolean checkForMissingFields(Collection<String> inputFields,
            Map<String, Integer> inputFieldIndexes,
            String[] header) {
        return true;
    }

    /**
     * Return the number of missing fields
     */
    private static long missingFieldCount(boolean[] gotFieldFlags) {
        long count = 0;

        for (int i = 0; i < gotFieldFlags.length; i++) {
            if (gotFieldFlags[i] == false) {
                ++count;
            }
        }

        return count;
    }
}
