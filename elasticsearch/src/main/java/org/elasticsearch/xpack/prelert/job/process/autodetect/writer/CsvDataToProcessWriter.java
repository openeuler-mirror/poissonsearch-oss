/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.autodetect.writer;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.xpack.prelert.job.AnalysisConfig;
import org.elasticsearch.xpack.prelert.job.DataCounts;
import org.elasticsearch.xpack.prelert.job.DataDescription;
import org.elasticsearch.xpack.prelert.job.process.autodetect.AutodetectProcess;
import org.elasticsearch.xpack.prelert.job.status.StatusReporter;
import org.elasticsearch.xpack.prelert.job.transform.TransformConfigs;
import org.supercsv.io.CsvListReader;
import org.supercsv.prefs.CsvPreference;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A writer for transforming and piping CSV data from an
 * inputstream to outputstream.
 * The data written to outputIndex is length encoded each record
 * consists of number of fields followed by length/value pairs.
 * See CLengthEncodedInputParser.h in the C++ code for a more
 * detailed description.
 * A control field is added to the end of each length encoded
 * line.
 */
class CsvDataToProcessWriter extends AbstractDataToProcessWriter {
    /**
     * Maximum number of lines allowed within a single CSV record.
     * <p>
     * In the scenario where there is a misplaced quote, there is
     * the possibility that it results to a single record expanding
     * over many lines. Supercsv will eventually deplete all memory
     * from the JVM. We set a limit to an arbitrary large number
     * to prevent that from happening. Unfortunately, supercsv
     * throws an exception which means we cannot recover and continue
     * reading new records from the next line.
     */
    private static final int MAX_LINES_PER_RECORD = 10000;

    public CsvDataToProcessWriter(boolean includeControlField, AutodetectProcess autodetectProcess,
            DataDescription dataDescription, AnalysisConfig analysisConfig,
            TransformConfigs transforms, StatusReporter statusReporter, Logger logger) {
        super(includeControlField, autodetectProcess, dataDescription, analysisConfig, transforms, statusReporter, logger);
    }

    /**
     * Read the csv inputIndex, transform to length encoded values and pipe to
     * the OutputStream. If any of the expected fields in the transform inputs,
     * analysis inputIndex or if the expected time field is missing from the CSV
     * header a exception is thrown
     */
    @Override
    public DataCounts write(InputStream inputStream, Supplier<Boolean> cancelled) throws IOException {
        CsvPreference csvPref = new CsvPreference.Builder(
                dataDescription.getQuoteCharacter(),
                dataDescription.getFieldDelimiter(),
                new String(new char[]{DataDescription.LINE_ENDING}))
                .maxLinesPerRow(MAX_LINES_PER_RECORD).build();

        statusReporter.startNewIncrementalCount();

        try (CsvListReader csvReader = new CsvListReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8), csvPref)) {
            String[] header = csvReader.getHeader(true);
            if (header == null) { // null if EoF

                return statusReporter.incrementalStats();
            }

            long inputFieldCount = Math.max(header.length - 1, 0); // time field doesn't count

            buildTransformsAndWriteHeader(header);

            //backing array for the inputIndex
            String[] inputRecord = new String[header.length];

            int maxIndex = 0;
            for (Integer index : inFieldIndexes.values()) {
                maxIndex = Math.max(index, maxIndex);
            }

            int numFields = outputFieldCount();
            String[] record = new String[numFields];

            List<String> line;
            while ((line = csvReader.read()) != null) {
                Arrays.fill(record, "");

                if (maxIndex >= line.size()) {
                    logger.warn("Not enough fields in csv record, expected at least " + maxIndex + ". " + line);

                    for (InputOutputMap inOut : inputOutputMap) {
                        if (inOut.inputIndex >= line.size()) {
                            statusReporter.reportMissingField();
                            continue;
                        }

                        String field = line.get(inOut.inputIndex);
                        record[inOut.outputIndex] = (field == null) ? "" : field;
                    }
                } else {
                    for (InputOutputMap inOut : inputOutputMap) {
                        String field = line.get(inOut.inputIndex);
                        record[inOut.outputIndex] = (field == null) ? "" : field;
                    }
                }

                fillRecordFromLine(line, inputRecord);
                applyTransformsAndWrite(cancelled, inputRecord, record, inputFieldCount);
            }

            // This function can throw
            statusReporter.finishReporting();
        }

        return statusReporter.incrementalStats();
    }

    private static void fillRecordFromLine(List<String> line, String[] record) {
        Arrays.fill(record, "");
        for (int i = 0; i < Math.min(line.size(), record.length); i++) {
            String value = line.get(i);
            if (value != null) {
                record[i] = value;
            }
        }
    }

    @Override
    protected boolean checkForMissingFields(Collection<String> inputFields, Map<String, Integer> inputFieldIndexes, String[] header) {
        for (String field : inputFields) {
            if (AnalysisConfig.AUTO_CREATED_FIELDS.contains(field)) {
                continue;
            }
            Integer index = inputFieldIndexes.get(field);
            if (index == null) {
                String msg = String.format(Locale.ROOT, "Field configured for analysis "
                        + "'%s' is not in the CSV header '%s'",
                        field, Arrays.toString(header));

                logger.error(msg);
                throw new IllegalArgumentException(msg);
            }
        }

        return true;
    }
}
