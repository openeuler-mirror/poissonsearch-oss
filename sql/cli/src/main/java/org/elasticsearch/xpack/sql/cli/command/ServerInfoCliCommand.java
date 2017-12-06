/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.cli.command;

import org.elasticsearch.xpack.sql.cli.CliTerminal;
import org.elasticsearch.xpack.sql.cli.net.protocol.InfoResponse;

import java.sql.SQLException;
import java.util.Locale;

public class ServerInfoCliCommand extends AbstractServerCliCommand {

    public ServerInfoCliCommand() {
    }

    @Override
    public boolean doHandle(CliTerminal terminal, CliSession cliSession, String line) {
        if (false == "info".equals(line.toLowerCase(Locale.ROOT))) {
            return false;
        }
        InfoResponse info;
        try {
            info = cliSession.getClient().serverInfo();
        } catch (SQLException e) {
            terminal.error("Error fetching server info", e.getMessage());
            return true;
        }
        terminal.line()
                .text("Node:").em(info.node)
                .text(" Cluster:").em(info.cluster)
                .text(" Version:").em(info.versionString)
                .ln();
        return true;
    }
}
