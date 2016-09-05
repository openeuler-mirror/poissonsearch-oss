/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.watch;

import org.elasticsearch.common.collect.MapBuilder;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static org.elasticsearch.xpack.watcher.support.WatcherUtils.responseToData;

public interface Payload extends ToXContent {

    Simple EMPTY = new Simple(Collections.<String, Object>emptyMap());

    Map<String, Object> data();

    class Simple implements Payload {

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
            return builder.map(data);
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
            return "simple[" + Objects.toString(data) + "]";
        }
    }

    class XContent extends Simple {

        public XContent(ToXContent response) throws IOException {
            super(responseToData(response));
        }

    }
}
