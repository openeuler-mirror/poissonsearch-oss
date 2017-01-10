/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ml.job.persistence;

import org.elasticsearch.client.Client;

import java.util.function.Function;

/**
 * TODO This is all just silly static typing shenanigans because Guice can't inject
 * anonymous lambdas.  This can all be removed once Guice goes away.
 */
public class JobDataDeleterFactory implements Function<String, JobDataDeleter> {

    private final Client client;

    public JobDataDeleterFactory(Client client) {
        this.client = client;
    }

    @Override
    public JobDataDeleter apply(String jobId) {
        return new JobDataDeleter(client, jobId);
    }
}
