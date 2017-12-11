/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.cli;

import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.elasticsearch.cli.Command;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.xpack.sql.cli.command.ClearScreenCliCommand;
import org.elasticsearch.xpack.sql.cli.command.CliCommand;
import org.elasticsearch.xpack.sql.cli.command.CliCommands;
import org.elasticsearch.xpack.sql.cli.command.CliSession;
import org.elasticsearch.xpack.sql.cli.command.FetchSeparatorCliCommand;
import org.elasticsearch.xpack.sql.cli.command.FetchSizeCliCommand;
import org.elasticsearch.xpack.sql.cli.command.PrintLogoCommand;
import org.elasticsearch.xpack.sql.cli.command.ServerInfoCliCommand;
import org.elasticsearch.xpack.sql.cli.command.ServerQueryCliCommand;
import org.elasticsearch.xpack.sql.client.shared.ClientException;
import org.elasticsearch.xpack.sql.client.shared.ConnectionConfiguration;
import org.elasticsearch.xpack.sql.client.shared.Version;

import java.io.IOException;
import java.net.ConnectException;
import java.util.Arrays;
import java.util.List;
import java.util.logging.LogManager;

public class Cli extends Command {
    private final OptionSpec<Boolean> debugOption;
    private final OptionSpec<Boolean> checkOption;
    private final OptionSpec<String> connectionString;

    private Cli() {
        super("Elasticsearch SQL CLI", Cli::configureLogging);
        this.debugOption = parser.acceptsAll(Arrays.asList("d", "debug"),
                "Enable debug logging")
                .withRequiredArg().ofType(Boolean.class)
                .defaultsTo(Boolean.parseBoolean(System.getProperty("cli.debug", "false")));
        this.checkOption = parser.acceptsAll(Arrays.asList("c", "check"),
                "Enable initial connection check on startup")
                .withRequiredArg().ofType(Boolean.class)
                .defaultsTo(Boolean.parseBoolean(System.getProperty("cli.check", "true")));
        this.connectionString = parser.nonOptions("uri");
    }

    /**
     * Use this VM Options to run in IntelliJ or Eclipse:
     * -Dorg.jline.terminal.type=xterm-256color
     * -Dorg.jline.terminal.jna=false
     * -Dorg.jline.terminal.jansi=false
     * -Dorg.jline.terminal.exec=false
     * -Dorg.jline.terminal.dumb=true
     */
    public static void main(String[] args) throws Exception {
        final Cli cli = new Cli();
        int status = cli.main(args, Terminal.DEFAULT);
        if (status != ExitCodes.OK) {
            exit(status);
        }
    }

    private static void configureLogging() {
        try {
            /* Initialize the logger from the a properties file we bundle. This makes sure
             * we get useful error messages from jLine. */
            LogManager.getLogManager().readConfiguration(Cli.class.getResourceAsStream("/logging.properties"));
        } catch (IOException ex) {
            throw new RuntimeException("cannot setup logging", ex);
        }
    }

    @Override
    protected void execute(org.elasticsearch.cli.Terminal terminal, OptionSet options) throws Exception {
        boolean debug = debugOption.value(options);
        boolean check = checkOption.value(options);
        List<String> args = connectionString.values(options);
        if (args.size() > 1) {
            throw new UserException(ExitCodes.USAGE, "expecting a single uri");
        }
        execute(args.size() == 1 ? args.get(0) : null, debug, check);
    }

    private void execute(String uri, boolean debug, boolean check) throws Exception {
        CliCommand cliCommand = new CliCommands(
                new PrintLogoCommand(),
                new ClearScreenCliCommand(),
                new FetchSizeCliCommand(),
                new FetchSeparatorCliCommand(),
                new ServerInfoCliCommand(),
                new ServerQueryCliCommand()
        );
        try (CliTerminal cliTerminal = new JLineTerminal()) {
            ConnectionBuilder connectionBuilder = new ConnectionBuilder(cliTerminal);
            ConnectionConfiguration con = connectionBuilder.buildConnection(uri);
            CliSession cliSession = new CliSession(new CliHttpClient(con));
            cliSession.setDebug(debug);
            if (check) {
                checkConnection(cliSession, cliTerminal, con);
            }
            new CliRepl(cliTerminal, cliSession, cliCommand).execute();
        }
    }

    private void checkConnection(CliSession cliSession, CliTerminal cliTerminal, ConnectionConfiguration con) throws UserException {
        try {
            cliSession.checkConnection();
        } catch (ClientException ex) {
            if (cliSession.isDebug()) {
                cliTerminal.error("Client Exception", ex.getMessage());
                cliTerminal.println();
                cliTerminal.printStackTrace(ex);
                cliTerminal.flush();
            }
            if (ex.getCause() != null && ex.getCause() instanceof ConnectException) {
                // Most likely Elasticsearch is not running
                throw new UserException(ExitCodes.IO_ERROR,
                        "Cannot connect to the server " + con.connectionString() + " - " + ex.getCause().getMessage());
            } else {
                // Most likely we connected to an old version of Elasticsearch or not Elasticsearch at all
                throw new UserException(ExitCodes.DATA_ERROR,
                        "Cannot communicate with the server " + con.connectionString() +
                                ". This version of CLI only works with Elasticsearch version " + Version.version());
            }
        }

    }
}
