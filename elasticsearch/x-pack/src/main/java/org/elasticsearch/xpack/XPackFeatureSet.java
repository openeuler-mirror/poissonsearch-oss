/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack;

import org.elasticsearch.common.io.stream.NamedWriteable;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;

/**
 *
 */
public interface XPackFeatureSet {

    String name();

    String description();

    boolean available();

    boolean enabled();

    Usage usage();

    abstract class Usage implements ToXContent, NamedWriteable {

        private static final String AVAILABLE_XFIELD = "available";
        private static final String ENABLED_XFIELD = "enabled";

        protected final String name;
        protected final boolean available;
        protected final boolean enabled;

        public Usage(StreamInput input) throws IOException {
            this(input.readString(), input.readBoolean(), input.readBoolean());
        }

        public Usage(String name, boolean available, boolean enabled) {
            this.name = name;
            this.available = available;
            this.enabled = enabled;
        }

        public String name() {
            return name;
        }

        public boolean available() {
            return available;
        }

        public boolean enabled() {
            return enabled;
        }

        @Override
        public String getWriteableName() {
            return writeableName(name);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeBoolean(available);
            out.writeBoolean(enabled);
        }

        @Override
        public final XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            innerXContent(builder, params);
            return builder.endObject();
        }

        protected void innerXContent(XContentBuilder builder, Params params) throws IOException {
            builder.field(AVAILABLE_XFIELD, available);
            builder.field(ENABLED_XFIELD, enabled);
        }

        public static String writeableName(String featureName) {
            return "xpack.usage." + featureName;
        }
    }

}
