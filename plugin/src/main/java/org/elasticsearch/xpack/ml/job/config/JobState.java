/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.config;

import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Jobs whether running or complete are in one of these states.
 * When a job is created it is initialised in to the state closed
 * i.e. it is not running.
 */
public enum JobState implements ToXContent, Writeable {

    CLOSING, CLOSED, OPENED, FAILED;

    public static JobState fromString(String name) {
        return valueOf(name.trim().toUpperCase(Locale.ROOT));
    }

    public static JobState fromStream(StreamInput in) throws IOException {
        int ordinal = in.readVInt();
        if (ordinal < 0 || ordinal >= values().length) {
            throw new IOException("Unknown public enum JobState {\n ordinal [" + ordinal + "]");
        }
        return values()[ordinal];
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        out.writeVInt(ordinal());
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.value(name().toLowerCase(Locale.ROOT));
        return builder;
    }

    @Override
    public boolean isFragment() {
        return true;
    }


    /**
     * @return {@code true} if state matches any of the given {@code candidates}
     */
    public boolean isAnyOf(JobState... candidates) {
        return Arrays.stream(candidates).anyMatch(candidate -> this == candidate);
    }

    @Override
    public String toString() {
        return name().toLowerCase(Locale.ROOT);
    }
}
