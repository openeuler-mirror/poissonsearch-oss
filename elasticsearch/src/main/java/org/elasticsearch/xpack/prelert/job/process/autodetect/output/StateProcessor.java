/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.prelert.job.process.autodetect.output;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.bytes.CompositeBytesReference;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.xpack.prelert.job.persistence.JobResultsPersister;

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads the autodetect persisted state and writes the results via the {@linkplain JobResultsPersister} passed in the constructor.
 */
public class StateProcessor extends AbstractComponent {

    private static final int READ_BUF_SIZE = 8192;
    private final JobResultsPersister persister;

    public StateProcessor(Settings settings, JobResultsPersister persister) {
        super(settings);
        this.persister = persister;
    }

    public void process(String jobId, InputStream in) {
        try {
            BytesReference bytesRef = null;
            int searchFrom = 0;
            byte[] readBuf = new byte[READ_BUF_SIZE];
            for (int bytesRead = in.read(readBuf); bytesRead != -1; bytesRead = in.read(readBuf)) {
                if (bytesRef == null) {
                    searchFrom = 0;
                    bytesRef = new BytesArray(readBuf, 0, bytesRead);
                } else {
                    searchFrom = bytesRef.length();
                    bytesRef = new CompositeBytesReference(bytesRef, new BytesArray(readBuf, 0, bytesRead));
                }
                bytesRef = splitAndPersist(jobId, bytesRef, searchFrom);
                readBuf = new byte[READ_BUF_SIZE];
            }
        } catch (IOException e) {
            logger.info(new ParameterizedMessage("[{}] Error reading autodetect state output", jobId), e);
        }
        logger.info("[{}] State output finished", jobId);
    }

    /**
     * Splits bulk data streamed from the C++ process on '\0' characters.  The
     * data is expected to be a series of Elasticsearch bulk requests in UTF-8 JSON
     * (as would be uploaded to the public REST API) separated by zero bytes ('\0').
     */
    private BytesReference splitAndPersist(String jobId, BytesReference bytesRef, int searchFrom) {
        int splitFrom = 0;
        while (true) {
            int nextZeroByte = findNextZeroByte(bytesRef, searchFrom, splitFrom);
            if (nextZeroByte == -1) {
                // No more zero bytes in this block
                break;
            }
            persister.persistBulkState(jobId, bytesRef.slice(splitFrom, nextZeroByte - splitFrom));
            splitFrom = nextZeroByte + 1;
        }
        if (splitFrom >= bytesRef.length()) {
            return null;
        }
        return bytesRef.slice(splitFrom, bytesRef.length() - splitFrom);
    }

    private static int findNextZeroByte(BytesReference bytesRef, int searchFrom, int splitFrom) {
        for (int i = Math.max(searchFrom, splitFrom); i < bytesRef.length(); ++i) {
            if (bytesRef.get(i) == 0) {
                return i;
            }
        }
        return -1;
    }
}

