/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.actions.slack.service;

/**
 *
 */
public interface SlackService {


    /**
     * @return The default slack account.
     */
    SlackAccount getDefaultAccount();

    /**
     * @return  The account identified by the given name. If the given name is {@code null} the default
     *          account will be returned.
     */
    SlackAccount getAccount(String accountName);
}
