/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.qa.sql.embed;

import org.elasticsearch.client.Client;

/**
 * Internal server used for testing without starting a new Elasticsearch instance.
 */
public class CliHttpServer extends ProtoHttpServer {
    public CliHttpServer(Client client) {
        super(client, new CliProtoHandler(client), "/_xpack/sql/cli");
    }

    @Override
    public String url() {
        return "http://" + super.url();
    }
}