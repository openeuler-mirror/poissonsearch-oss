/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
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

package org.elasticsearch.ingest.processor.trim;

import org.elasticsearch.ingest.processor.AbstractStringProcessor;

/**
 * Processor that trims the content of string fields.
 * Throws exception is the field is not of type string.
 */
public class TrimProcessor extends AbstractStringProcessor {

    public static final String TYPE = "trim";

    TrimProcessor(String field) {
        super(field);
    }

    @Override
    protected String process(String value) {
        return value.trim();
    }

    @Override
    public String getType() {
        return TYPE;
    }

    public static class Factory extends AbstractStringProcessor.Factory<TrimProcessor> {
        @Override
        protected TrimProcessor newProcessor(String field) {
            return new TrimProcessor(field);
        }
    }
}

