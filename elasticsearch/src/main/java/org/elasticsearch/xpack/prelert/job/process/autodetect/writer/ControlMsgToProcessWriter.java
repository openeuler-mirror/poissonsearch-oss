/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.autodetect.writer;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.elasticsearch.xpack.prelert.job.process.autodetect.params.DataLoadParams;
import org.elasticsearch.xpack.prelert.job.process.autodetect.params.InterimResultsParams;

/**
 * A writer for sending control messages to the C++ autodetect process.
 * The data written to outputIndex is length encoded.
 */
public class ControlMsgToProcessWriter {
    /**
     * This should be the same size as the buffer in the C++ autodetect process.
     */
    public static final int FLUSH_SPACES_LENGTH = 8192;

    /**
     * This must match the code defined in the api::CAnomalyDetector C++ class.
     */
    private static final String FLUSH_MESSAGE_CODE = "f";

    /**
     * This must match the code defined in the api::CAnomalyDetector C++ class.
     */
    private static final String INTERIM_MESSAGE_CODE = "i";

    /**
     * This must match the code defined in the api::CAnomalyDetector C++ class.
     */
    public static final String RESET_BUCKETS_MESSAGE_CODE = "r";

    /**
     * This must match the code defined in the api::CAnomalyDetector C++ class.
     */
    private static final String ADVANCE_TIME_MESSAGE_CODE = "t";

    /**
     * This must match the code defined in the api::CAnomalyDetector C++ class.
     */
    public static final String UPDATE_MESSAGE_CODE = "u";

    /**
     * An number to uniquely identify each flush so that subsequent code can
     * wait for acknowledgement of the correct flush.
     */
    private static AtomicLong ms_FlushNumber = new AtomicLong(1);

    private final LengthEncodedWriter lengthEncodedWriter;
    private final int numberOfAnalysisFields;

    /**
     * Construct the control message writer with a LengthEncodedWriter
     *
     * @param lengthEncodedWriter
     *            the writer
     * @param numberOfAnalysisFields
     *            The number of fields configured for analysis not including the
     *            time field
     */
    public ControlMsgToProcessWriter(LengthEncodedWriter lengthEncodedWriter, int numberOfAnalysisFields) {
        this.lengthEncodedWriter = Objects.requireNonNull(lengthEncodedWriter);
        this.numberOfAnalysisFields= numberOfAnalysisFields;
    }

    /**
     * Create the control message writer with a OutputStream. A
     * LengthEncodedWriter is created on the OutputStream parameter
     *
     * @param os
     *            the output stream
     * @param numberOfAnalysisFields
     *            The number of fields configured for analysis not including the
     *            time field
     */
    public static ControlMsgToProcessWriter create(OutputStream os, int numberOfAnalysisFields) {
        return new ControlMsgToProcessWriter(new LengthEncodedWriter(os), numberOfAnalysisFields);
    }

    /**
     * Send an instruction to calculate interim results to the C++ autodetect process.
     *
     * @param params Parameters indicating whether interim results should be written
     *               and for which buckets
     */
    public void writeCalcInterimMessage(InterimResultsParams params) throws IOException {
        if (params.shouldAdvanceTime()) {
            writeMessage(ADVANCE_TIME_MESSAGE_CODE + params.getAdvanceTime());
        }
        if (params.shouldCalculateInterim()) {
            writeControlCodeFollowedByTimeRange(INTERIM_MESSAGE_CODE, params.getStart(), params.getEnd());
        }
    }

    /**
     * Send a flush message to the C++ autodetect process.
     * This actually consists of two messages: one to carry the flush ID and the
     * other (which might not be processed until much later) to fill the buffers
     * and force prior messages through.
     *
     * @return an ID for this flush that will be echoed back by the C++
     * autodetect process once it is complete.
     */
    public String writeFlushMessage() throws IOException {
        String flushId = Long.toString(ms_FlushNumber.getAndIncrement());
        writeMessage(FLUSH_MESSAGE_CODE + flushId);

        char[] spaces = new char[FLUSH_SPACES_LENGTH];
        Arrays.fill(spaces, ' ');
        writeMessage(new String(spaces));

        lengthEncodedWriter.flush();
        return flushId;
    }

    public void writeUpdateConfigMessage(String config) throws IOException {
        writeMessage(UPDATE_MESSAGE_CODE + config);
    }

    public void writeResetBucketsMessage(DataLoadParams params) throws IOException {
        writeControlCodeFollowedByTimeRange(RESET_BUCKETS_MESSAGE_CODE, params.getStart(), params.getEnd());
    }

    private void writeControlCodeFollowedByTimeRange(String code, String start, String end)
            throws IOException {
        StringBuilder message = new StringBuilder(code);
        if (start.isEmpty() == false) {
            message.append(start);
            message.append(' ');
            message.append(end);
        }
        writeMessage(message.toString());
    }

    /**
     * Transform the supplied control message to length encoded values and
     * write to the OutputStream.
     * The number of blank fields to make up a full record is deduced from
     * <code>analysisConfig</code>.
     *
     * @param message The control message to write.
     */
    private void writeMessage(String message) throws IOException {

        // The fields consist of all the analysis fields plus the time and the
        // control field, hence + 2
        lengthEncodedWriter.writeNumFields(numberOfAnalysisFields + 2);

        // Write blank values for all analysis fields and the time
        for (int i = -1; i < numberOfAnalysisFields; ++i) {
            lengthEncodedWriter.writeField("");
        }

        // The control field comes last
        lengthEncodedWriter.writeField(message);
    }

}
