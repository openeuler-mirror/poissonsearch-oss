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

package org.elasticsearch.ingest.processor.set;

import org.elasticsearch.test.ESTestCase;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.CoreMatchers.equalTo;

public class SetProcessorFactoryTests extends ESTestCase {

    public void testCreate() throws Exception {
        SetProcessor.Factory factory = new SetProcessor.Factory();
        Map<String, Object> config = new HashMap<>();
        config.put("field", "field1");
        config.put("value", "value1");
        SetProcessor setProcessor = factory.create(config);
        assertThat(setProcessor.getField(), equalTo("field1"));
        assertThat(setProcessor.getValue(), equalTo("value1"));
    }

    public void testCreateNoFieldPresent() throws Exception {
        SetProcessor.Factory factory = new SetProcessor.Factory();
        Map<String, Object> config = new HashMap<>();
        config.put("value", "value1");
        try {
            factory.create(config);
            fail("factory create should have failed");
        } catch(IllegalArgumentException e) {
            assertThat(e.getMessage(), equalTo("required property [field] is missing"));
        }
    }

    public void testCreateNoValuePresent() throws Exception {
        SetProcessor.Factory factory = new SetProcessor.Factory();
        Map<String, Object> config = new HashMap<>();
        config.put("field", "field1");
        try {
            factory.create(config);
            fail("factory create should have failed");
        } catch(IllegalArgumentException e) {
            assertThat(e.getMessage(), equalTo("required property [value] is missing"));
        }
    }

    public void testCreateNullValue() throws Exception {
        SetProcessor.Factory factory = new SetProcessor.Factory();
        Map<String, Object> config = new HashMap<>();
        config.put("field", "field1");
        config.put("value", null);
        try {
            factory.create(config);
            fail("factory create should have failed");
        } catch(IllegalArgumentException e) {
            assertThat(e.getMessage(), equalTo("required property [value] is missing"));
        }
    }
}
