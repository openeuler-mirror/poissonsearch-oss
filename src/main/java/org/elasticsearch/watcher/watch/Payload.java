/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.watch;

import org.elasticsearch.common.Strings;
import org.elasticsearch.common.base.MoreObjects;
import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.watcher.WatcherException;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.watcher.support.WatcherUtils.responseToData;

/**
 *
 */
public interface Payload extends ToXContent {

    Map<String, Object> data();

    static class Simple implements Payload {

        private final Map<String, Object> data;

        public Simple() {
            this(new HashMap<String, Object>());
        }

        public Simple(String key, Object value) {
            this(new MapBuilder<String, Object>().put(key, value).map());
        }

        public Simple(Map<String, Object> data) {
            this.data = data;
        }

        @Override
        public Map<String, Object> data() {
            return data;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return builder.value(data);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Simple simple = (Simple) o;

            if (!data.equals(simple.data)) return false;

            return true;
        }

        @Override
        public int hashCode() {
            return data.hashCode();
        }

        @Override
        public String toString() {
            return "simple_input[" + Objects.toString(data) + "]";
        }
    }

    static class XContent extends Simple {

        public XContent(XContentParser parser) {
            super(mapOrdered(parser));
        }

        public XContent(ToXContent response) {
            super(responseToData(response));
        }

        private static Map<String, Object> mapOrdered(XContentParser parser) {
            try {
                return parser.mapOrdered();
            } catch (IOException ioe) {
                throw new WatcherException("could not build a payload out of xcontent", ioe);
            }
        }
    }
}
