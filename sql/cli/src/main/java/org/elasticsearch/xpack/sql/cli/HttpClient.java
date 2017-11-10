/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.cli;

import org.elasticsearch.xpack.sql.client.shared.Bytes;
import org.elasticsearch.xpack.sql.client.shared.CheckedConsumer;
import org.elasticsearch.xpack.sql.client.shared.ClientException;
import org.elasticsearch.xpack.sql.client.shared.JreHttpUrlConnection;

import java.io.DataOutput;
import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;

class HttpClient {

    private final CliConfiguration cfg;

    HttpClient(CliConfiguration cfg) {
        this.cfg = cfg;
    }

    Bytes post(CheckedConsumer<DataOutput, IOException> os) {
        try {
            return AccessController.doPrivileged((PrivilegedAction<Bytes>) () -> {
                return JreHttpUrlConnection.http(cfg.asUrl(), cfg, con -> {
                    return con.post(os);
                });
            });
        } catch (ClientException ex) {
            throw new RuntimeException("Transport failure", ex);
        }
    }
}