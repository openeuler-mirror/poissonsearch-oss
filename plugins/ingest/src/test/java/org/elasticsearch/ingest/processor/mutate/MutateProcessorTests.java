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

package org.elasticsearch.ingest.processor.mutate;

import org.elasticsearch.ingest.Data;
import org.elasticsearch.ingest.processor.Processor;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;


public class MutateProcessorTests extends ESTestCase {
    private Data data;

    @Before
    public void setData() {
        Map<String, Object> document = new HashMap<>();
        document.put("foo", "bar");
        document.put("alpha", "aBcD");
        document.put("num", "64");
        document.put("to_strip", " clean    ");
        document.put("arr", Arrays.asList("1", "2", "3"));
        document.put("ip", "127.0.0.1");
        Map<String, Object> fizz = new HashMap<>();
        fizz.put("buzz", "hello world");
        document.put("fizz", fizz);

        data = new Data("index", "type", "id", document);
    }

    public void testUpdate() throws IOException {
        Map<String, Object> update = new HashMap<>();
        update.put("foo", 123);
        Processor processor = new MutateProcessor(update, null, null, null, null, null, null, null, null, null);
        processor.execute(data);
        assertThat(data.getDocument().size(), equalTo(7));
        assertThat(data.getProperty("foo"), equalTo(123));
    }

    public void testRename() throws IOException {
        Map<String, String> rename = new HashMap<>();
        rename.put("foo", "bar");
        Processor processor = new MutateProcessor(null, rename, null, null, null, null, null, null, null, null);
        processor.execute(data);
        assertThat(data.getDocument().size(), equalTo(7));
        assertThat(data.getProperty("bar"), equalTo("bar"));
        assertThat(data.containsProperty("foo"), is(false));
    }

    public void testConvert() throws IOException {
        Map<String, String> convert = new HashMap<>();
        convert.put("num", "integer");
        Processor processor = new MutateProcessor(null, null, convert, null, null, null, null, null, null, null);
        processor.execute(data);
        assertThat(data.getDocument().size(), equalTo(7));
        assertThat(data.getProperty("num"), equalTo(64));
    }

    public void testConvertNullField() throws IOException {
        Map<String, String> convert = new HashMap<>();
        convert.put("null", "integer");
        Processor processor = new MutateProcessor(null, null, convert, null, null, null, null, null, null, null);
        try {
            processor.execute(data);
            fail("processor execute should have failed");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), equalTo("Field \"null\" is null, cannot be converted to a/an integer"));
        }
    }

    public void testConvertList() throws IOException {
        Map<String, String> convert = new HashMap<>();
        convert.put("arr", "integer");
        Processor processor = new MutateProcessor(null, null, convert, null, null, null, null, null, null, null);
        processor.execute(data);
        assertThat(data.getDocument().size(), equalTo(7));
        assertThat(data.getProperty("arr"), equalTo(Arrays.asList(1, 2, 3)));
    }

    public void testSplit() throws IOException {
        Map<String, String> split = new HashMap<>();
        split.put("ip", "\\.");
        Processor processor = new MutateProcessor(null, null, null, split, null, null, null, null, null, null);
        processor.execute(data);
        assertThat(data.getDocument().size(), equalTo(7));
        assertThat(data.getProperty("ip"), equalTo(Arrays.asList("127", "0", "0", "1")));
    }

    public void testGsub() throws IOException {
        List<GsubExpression> gsubExpressions = Collections.singletonList(new GsubExpression("ip", Pattern.compile("\\."), "-"));
        Processor processor = new MutateProcessor(null, null, null, null, gsubExpressions, null, null, null, null, null);
        processor.execute(data);
        assertThat(data.getDocument().size(), equalTo(7));
        assertThat(data.getProperty("ip"), equalTo("127-0-0-1"));
    }

    public void testGsub_NullValue() throws IOException {
        List<GsubExpression> gsubExpressions = Collections.singletonList(new GsubExpression("null_field", Pattern.compile("\\."), "-"));
        Processor processor = new MutateProcessor(null, null, null, null, gsubExpressions, null, null, null, null, null);
        try {
            processor.execute(data);
            fail("processor execution should have failed");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), equalTo("Field \"null_field\" is null, cannot match pattern."));
        }
    }

    public void testJoin() throws IOException {
        HashMap<String, String> join = new HashMap<>();
        join.put("arr", "-");
        Processor processor = new MutateProcessor(null, null, null, null, null, join, null, null, null, null);
        processor.execute(data);
        assertThat(data.getDocument().size(), equalTo(7));
        assertThat(data.getProperty("arr"), equalTo("1-2-3"));
    }

    public void testRemove() throws IOException {
        List<String> remove = Arrays.asList("foo", "ip");
        Processor processor = new MutateProcessor(null, null, null, null, null, null, remove, null, null, null);
        processor.execute(data);
        assertThat(data.getDocument().size(), equalTo(5));
        assertThat(data.getProperty("foo"), nullValue());
        assertThat(data.getProperty("ip"), nullValue());
    }

    public void testTrim() throws IOException {
        List<String> trim = Arrays.asList("to_strip", "foo");
        Processor processor = new MutateProcessor(null, null, null, null, null, null, null, trim, null, null);
        processor.execute(data);
        assertThat(data.getDocument().size(), equalTo(7));
        assertThat(data.getProperty("foo"), equalTo("bar"));
        assertThat(data.getProperty("to_strip"), equalTo("clean"));
    }

    public void testUppercase() throws IOException {
        List<String> uppercase = Collections.singletonList("foo");
        Processor processor = new MutateProcessor(null, null, null, null, null, null, null, null, uppercase, null);
        processor.execute(data);
        assertThat(data.getDocument().size(), equalTo(7));
        assertThat(data.getProperty("foo"), equalTo("BAR"));
    }

    public void testLowercase() throws IOException {
        List<String> lowercase = Collections.singletonList("alpha");
        Processor processor = new MutateProcessor(null, null, null, null, null, null, null, null, null, lowercase);
        processor.execute(data);
        assertThat(data.getDocument().size(), equalTo(7));
        assertThat(data.getProperty("alpha"), equalTo("abcd"));
    }
}
