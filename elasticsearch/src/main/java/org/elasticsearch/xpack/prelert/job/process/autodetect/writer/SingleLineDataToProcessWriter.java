/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
import org.apache.logging.log4j.Logger;

import org.elasticsearch.xpack.prelert.job.AnalysisConfig;
import org.elasticsearch.xpack.prelert.job.DataCounts;
import org.elasticsearch.xpack.prelert.job.DataDescription;
import org.elasticsearch.xpack.prelert.job.process.autodetect.AutodetectProcess;
import org.elasticsearch.xpack.prelert.job.status.StatusReporter;
import org.elasticsearch.xpack.prelert.job.transform.TransformConfigs;

/**
 * This writer is used for reading inputIndex data that are unstructured and
 * each record is a single line. The writer applies transforms and pipes
 * the records into length encoded outputIndex.
 * <p>
 * This writer is expected only to be used in combination of transforms
 * that will extract the time and the other fields used in the analysis.
 * <p>
 * Records for which no time can be extracted will be ignored.
 */
public class SingleLineDataToProcessWriter extends AbstractDataToProcessWriter {
    private static final String RAW = "raw";

    protected SingleLineDataToProcessWriter(boolean includeControlField, AutodetectProcess autodetectProcess,
            DataDescription dataDescription, AnalysisConfig analysisConfig,
            TransformConfigs transformConfigs, StatusReporter statusReporter, Logger logger) {
        super(includeControlField, autodetectProcess, dataDescription, analysisConfig, transformConfigs, statusReporter, logger);
    }

    @Override
    public DataCounts write(InputStream inputStream, Supplier<Boolean> cancelled) throws IOException {
        statusReporter.startNewIncrementalCount();

        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String[] header = {RAW};
            buildTransformsAndWriteHeader(header);

            int numFields = outputFieldCount();
            String[] record = new String[numFields];

            for (String line = bufferedReader.readLine(); line != null;
                    line = bufferedReader.readLine()) {
                Arrays.fill(record, "");
                applyTransformsAndWrite(cancelled, new String[]{line}, record, 1);
            }
            statusReporter.finishReporting();
        }

        return statusReporter.incrementalStats();
    }

    @Override
    protected boolean checkForMissingFields(Collection<String> inputFields,
            Map<String, Integer> inputFieldIndexes, String[] header) {
        return true;
    }
}
