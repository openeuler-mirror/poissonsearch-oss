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

package org.elasticsearch.common.cli;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.StreamsUtils;
import org.junit.After;
import org.junit.Before;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;

public abstract class CliToolTestCase extends ESTestCase {

    @Before
    @SuppressForbidden(reason = "sets es.default.path.home during tests")
    public void setPathHome() {
        System.setProperty("es.default.path.home", createTempDir().toString());
    }

    @After
    @SuppressForbidden(reason = "clears es.default.path.home during tests")
    public void clearPathHome() {
        System.clearProperty("es.default.path.home");
    }

    public static String[] args(String command) {
        if (!Strings.hasLength(command)) {
            return Strings.EMPTY_ARRAY;
        }
        return command.split("\\s+");
    }

    /**
     * A terminal implementation that discards everything
     */
    public static class MockTerminal extends Terminal {

        @Override
        protected void doPrint(String msg) {}

        @Override
        public String readText(String prompt) {
            return null;
        }

        @Override
        public char[] readSecret(String prompt) {
            return new char[0];
        }
    }

    /**
     * A terminal implementation that captures everything written to it
     */
    public static class CaptureOutputTerminal extends MockTerminal {

        List<String> terminalOutput = new ArrayList<>();

        public CaptureOutputTerminal() {
            this(Verbosity.NORMAL);
        }

        public CaptureOutputTerminal(Verbosity verbosity) {
            setVerbosity(verbosity);
        }

        @Override
        protected void doPrint(String msg) {
            terminalOutput.add(msg);
        }

        public List<String> getTerminalOutput() {
            return terminalOutput;
        }
    }

    public static void assertTerminalOutputContainsHelpFile(CliToolTestCase.CaptureOutputTerminal terminal, String classPath) throws IOException {
        List<String> nonEmptyLines = new ArrayList<>();
        for (String line : terminal.getTerminalOutput()) {
            String originalPrintedLine = line.replaceAll(System.lineSeparator(), "");
            if (Strings.isNullOrEmpty(originalPrintedLine)) {
                nonEmptyLines.add(originalPrintedLine);
            }
        }
        assertThat(nonEmptyLines, hasSize(greaterThan(0)));

        String expectedDocs = StreamsUtils.copyToStringFromClasspath(classPath);
        for (String nonEmptyLine : nonEmptyLines) {
            assertThat(expectedDocs, containsString(nonEmptyLine.replaceAll(System.lineSeparator(), "")));
        }
    }
}
