/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.alerts;

import org.elasticsearch.common.io.FastStringReader;
import org.elasticsearch.common.io.Streams;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.joda.time.DateTimeZone;
import org.elasticsearch.common.joda.time.format.ISODateTimeFormat;

import java.io.IOException;
import java.util.Properties;

/**
 */
public class AlertsBuild {

    public static final AlertsBuild CURRENT;

    static {
        String hash = "NA";
        String hashShort = "NA";
        String timestamp = "NA";

        try {
            String properties = Streams.copyToStringFromClasspath("/alerts-build.properties");
            Properties props = new Properties();
            props.load(new FastStringReader(properties));
            hash = props.getProperty("hash", hash);
            if (!hash.equals("NA")) {
                hashShort = hash.substring(0, 7);
            }
            String gitTimestampRaw = props.getProperty("timestamp");
            if (gitTimestampRaw != null) {
                timestamp = ISODateTimeFormat.dateTimeNoMillis().withZone(DateTimeZone.UTC).print(Long.parseLong(gitTimestampRaw));
            }
        } catch (Exception e) {
            // just ignore...
        }

        CURRENT = new AlertsBuild(hash, hashShort, timestamp);
    }

    private final String hash;
    private final String hashShort;
    private final String timestamp;

    AlertsBuild(String hash, String hashShort, String timestamp) {
        this.hash = hash;
        this.hashShort = hashShort;
        this.timestamp = timestamp;
    }

    public String hash() {
        return hash;
    }

    public String hashShort() {
        return hashShort;
    }

    public String timestamp() {
        return timestamp;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        AlertsBuild that = (AlertsBuild) o;

        if (!hash.equals(that.hash)) return false;
        if (!hashShort.equals(that.hashShort)) return false;
        if (!timestamp.equals(that.timestamp)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = hash.hashCode();
        result = 31 * result + hashShort.hashCode();
        result = 31 * result + timestamp.hashCode();
        return result;
    }

    public static AlertsBuild readBuild(StreamInput in) throws IOException {
        String hash = in.readString();
        String hashShort = in.readString();
        String timestamp = in.readString();
        return new AlertsBuild(hash, hashShort, timestamp);
    }

    public static void writeBuild(AlertsBuild build, StreamOutput out) throws IOException {
        out.writeString(build.hash());
        out.writeString(build.hashShort());
        out.writeString(build.timestamp());
    }
}
