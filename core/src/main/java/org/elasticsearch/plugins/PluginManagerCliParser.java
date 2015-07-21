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

package org.elasticsearch.plugins;

import com.google.common.base.Strings;
import org.apache.commons.cli.CommandLine;
import org.elasticsearch.common.cli.CliTool;
import org.elasticsearch.common.cli.CliToolConfig;
import org.elasticsearch.common.cli.Terminal;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.logging.log4j.LogConfigurator;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.internal.InternalSettingsPreparer;
import org.elasticsearch.plugins.PluginManager.OutputMode;

import java.io.IOException;
import java.util.Locale;

import static org.elasticsearch.common.cli.CliToolConfig.Builder.cmd;
import static org.elasticsearch.common.cli.CliToolConfig.Builder.option;
import static org.elasticsearch.common.settings.Settings.EMPTY;

public class PluginManagerCliParser extends CliTool {

    // By default timeout is 0 which means no timeout
    public static final TimeValue DEFAULT_TIMEOUT = TimeValue.timeValueMillis(0);

    private static final CliToolConfig CONFIG = CliToolConfig.config("plugin", PluginManagerCliParser.class)
            .cmds(ListPlugins.CMD, Install.CMD, Remove.CMD)
            .build();

    public static void main(String[] args) {
        Tuple<Settings, Environment> initialSettings = InternalSettingsPreparer.prepareSettings(EMPTY, true, Terminal.DEFAULT);
        LogConfigurator.configure(initialSettings.v1());
        int status = new PluginManagerCliParser().execute(args);
        System.exit(status);
    }

    public PluginManagerCliParser() {
        super(CONFIG);
    }

    public PluginManagerCliParser(Terminal terminal) {
        super(CONFIG, terminal);
    }

    @Override
    protected Command parse(String cmdName, CommandLine cli) throws Exception {
        switch (cmdName.toLowerCase(Locale.ROOT)) {
            case Install.NAME:
                return Install.parse(terminal, cli);
            case ListPlugins.NAME:
                return ListPlugins.parse(terminal, cli);
            case Remove.NAME:
                return Remove.parse(terminal, cli);
            default:
                assert false : "can't get here as cmd name is validated before this method is called";
                return exitCmd(ExitStatus.USAGE);
        }
    }

    /**
     * List all installed plugins
     */
    static class ListPlugins extends CliTool.Command {

        private static final String NAME = "list";

        private static final CliToolConfig.Cmd CMD = cmd(NAME, ListPlugins.class).build();
        private final OutputMode outputMode;

        public static Command parse(Terminal terminal, CommandLine cli) {
            OutputMode outputMode = OutputMode.DEFAULT;
            if (cli.hasOption("s")) {
                outputMode = OutputMode.SILENT;
            }
            if (cli.hasOption("v")) {
                outputMode = OutputMode.VERBOSE;
            }

            return new ListPlugins(terminal, outputMode);
        }

        ListPlugins(Terminal terminal, OutputMode outputMode) {
            super(terminal);
            this.outputMode = outputMode;
        }

        @Override
        public ExitStatus execute(Settings settings, Environment env) throws Exception {
            PluginManager pluginManager = new PluginManager(env, null, outputMode, DEFAULT_TIMEOUT);
            pluginManager.listInstalledPlugins(terminal);
            return ExitStatus.OK;
        }
    }

    /**
     * Remove a plugin
     */
    static class Remove extends CliTool.Command {

        private static final String NAME = "remove";

        private static final CliToolConfig.Cmd CMD = cmd(NAME, Remove.class).build();

        public static Command parse(Terminal terminal, CommandLine cli) {
            String[] args = cli.getArgs();
            if (args.length == 0) {
                return exitCmd(ExitStatus.USAGE, terminal, "plugin name is missing (type -h for help)");
            }

            OutputMode outputMode = OutputMode.DEFAULT;
            if (cli.hasOption("s")) {
                outputMode = OutputMode.SILENT;
            }
            if (cli.hasOption("v")) {
                outputMode = OutputMode.VERBOSE;
            }

            return new Remove(terminal, outputMode, args[0]);
        }

        private OutputMode outputMode;
        final String pluginName;

        Remove(Terminal terminal, OutputMode outputMode, String pluginToRemove) {
            super(terminal);
            this.outputMode = outputMode;
            this.pluginName = pluginToRemove;
        }

        @Override
        public ExitStatus execute(Settings settings, Environment env) throws Exception {

            PluginManager pluginManager = new PluginManager(env, null, outputMode, DEFAULT_TIMEOUT);
            terminal.println("-> Removing " + Strings.nullToEmpty(pluginName) + "...");
            pluginManager.removePlugin(pluginName, terminal);
            return ExitStatus.OK;
        }
    }

    /**
     * Installs a plugin
     */
    static class Install extends Command {

        private static final String NAME = "install";

        private static final CliToolConfig.Cmd CMD = cmd(NAME, Install.class)
                .options(option("u", "url").required(false).hasArg(true))
                .options(option("t", "timeout").required(false).hasArg(false))
                .build();

        static Command parse(Terminal terminal, CommandLine cli) {
            String[] args = cli.getArgs();
            if ((args == null) || (args.length == 0)) {
                return exitCmd(ExitStatus.USAGE, terminal, "plugin name is missing (type -h for help)");
            }

            String name = args[0];
            TimeValue timeout = TimeValue.parseTimeValue(cli.getOptionValue("t"), DEFAULT_TIMEOUT, "cli");
            String url = cli.getOptionValue("u");

            OutputMode outputMode = OutputMode.DEFAULT;
            if (cli.hasOption("s")) {
                outputMode = OutputMode.SILENT;
            }
            if (cli.hasOption("v")) {
                outputMode = OutputMode.VERBOSE;
            }

            return new Install(terminal, name, outputMode, url, timeout);
        }

        final String name;
        private OutputMode outputMode;
        final String url;
        final TimeValue timeout;

        Install(Terminal terminal, String name, OutputMode outputMode, String url, TimeValue timeout) {
            super(terminal);
            this.name = name;
            this.outputMode = outputMode;
            this.url = url;
            this.timeout = timeout;
        }

        @Override
        public ExitStatus execute(Settings settings, Environment env) throws Exception {
            PluginManager pluginManager = new PluginManager(env, url, outputMode, timeout);
            terminal.println("-> Installing " + Strings.nullToEmpty(name) + "...");
            pluginManager.downloadAndExtract(name, terminal);
            return ExitStatus.OK;
        }
    }
}