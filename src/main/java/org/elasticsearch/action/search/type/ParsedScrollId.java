/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.search.type;

import org.elasticsearch.common.collect.Tuple;

import java.util.Map;

/**
 *
 */
public class ParsedScrollId {

    public static final String QUERY_THEN_FETCH_TYPE = "queryThenFetch";

    public static final String QUERY_AND_FETCH_TYPE = "queryAndFetch";

    public static final String SCAN = "scan";

    private final String source;

    private final String type;

    private final Tuple<String, Long>[] context;

    private final Map<String, String> attributes;

    public ParsedScrollId(String source, String type, Tuple<String, Long>[] context, Map<String, String> attributes) {
        this.source = source;
        this.type = type;
        this.context = context;
        this.attributes = attributes;
    }

    public String source() {
        return source;
    }

    public String type() {
        return type;
    }

    public Tuple<String, Long>[] context() {
        return context;
    }

    public Map<String, String> attributes() {
        return this.attributes;
    }
}
