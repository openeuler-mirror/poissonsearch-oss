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

package org.elasticsearch.common.logging;

import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.elasticsearch.common.logging.LoggingOutputStream.DEFAULT_BUFFER_LENGTH;
import static org.elasticsearch.common.logging.LoggingOutputStream.MAX_BUFFER_LENGTH;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

public class LoggingOutputStreamTests extends ESTestCase {

    class TestLoggingOutputStream extends LoggingOutputStream {
        List<String> lines = new ArrayList<>();

        TestLoggingOutputStream() {
            super(null, null);
        }

        @Override
        void log(String msg) {
            lines.add(msg);
        }
    }

    TestLoggingOutputStream loggingStream;
    PrintStream printStream;

    @Before
    public void createStream() throws Exception {
        loggingStream = new TestLoggingOutputStream();
        printStream = new PrintStream(loggingStream, false, StandardCharsets.UTF_8.name());
    }

    public void testEmptyLine() {
        printStream.println("");
        assertTrue(loggingStream.lines.isEmpty());
        printStream.flush();
        assertTrue(loggingStream.lines.isEmpty());
    }

    public void testNull() {
        printStream.write(0);
        printStream.flush();
        assertTrue(loggingStream.lines.isEmpty());
    }

    public void testFlushOnNewline() {
        printStream.println("hello");
        printStream.println("world");
        assertThat(loggingStream.lines, contains("hello", "world"));
    }

    public void testBufferExtension() {
        String longStr = randomAlphaOfLength(DEFAULT_BUFFER_LENGTH);
        String extraLongStr = randomAlphaOfLength(DEFAULT_BUFFER_LENGTH + 1);
        printStream.println(longStr);
        assertThat(loggingStream.threadLocal.get().bytes.length, equalTo(DEFAULT_BUFFER_LENGTH));
        printStream.println(extraLongStr);
        assertThat(loggingStream.lines, contains(longStr, extraLongStr));
        assertThat(loggingStream.threadLocal.get().bytes.length, equalTo(DEFAULT_BUFFER_LENGTH));
    }

    public void testMaxBuffer() {
        String longStr = randomAlphaOfLength(MAX_BUFFER_LENGTH);
        String extraLongStr = longStr + "OVERFLOW";
        printStream.println(longStr);
        printStream.println(extraLongStr);
        assertThat(loggingStream.lines, contains(longStr, longStr, "OVERFLOW"));
    }

    public void testClosed() {
        loggingStream.close();
        IOException e = expectThrows(IOException.class, () -> loggingStream.write('a'));
        assertThat(e.getMessage(), containsString("buffer closed"));
    }

    public void testThreadIsolation() throws Exception {
        printStream.print("from thread 1");
        Thread thread2 = new Thread(() -> {
            printStream.println("from thread 2");
        });
        thread2.start();
        thread2.join();
        printStream.flush();
        assertThat(loggingStream.lines, contains("from thread 2", "from thread 1"));
    }
}
