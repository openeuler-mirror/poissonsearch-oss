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

package org.elasticsearch.cli;

import joptsimple.OptionSet;
import org.elasticsearch.common.cli.Terminal;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

/**
 * A base test case for cli tools.
 */
public abstract class CommandTestCase extends ESTestCase {

    protected final MockTerminal terminal = new MockTerminal();

    @Before
    public void resetTerminal() {
        terminal.reset();
        terminal.setVerbosity(Terminal.Verbosity.NORMAL);
    }

    protected abstract Command newCommand();

    public String execute(String... args) throws Exception {
        Command command = newCommand();
        OptionSet options = command.parser.parse(args);
        command.execute(terminal, options);
        return terminal.getOutput();
    }
}
