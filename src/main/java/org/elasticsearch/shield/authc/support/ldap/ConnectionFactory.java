/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.support.ldap;

import org.elasticsearch.shield.authc.support.SecuredString;

/**
 * This factory holds settings needed for authenticating to LDAP and creating LdapConnections.
 * Each created LdapConnection needs to be closed or else connections will pill up consuming resources.
 *
 * A standard looking usage pattern could look like this:
 <pre>
    ConnectionFactory factory = ...
    try (LdapConnection session = factory.open(...)) {
        ...do stuff with the session
    }
 </pre>
 */
public interface ConnectionFactory {

    static final String URLS_SETTING = "url";

    /**
     * Authenticates the given user and opens a new connection that bound to it (meaning, all operations
     * under the returned connection will be executed on behalf of the authenticated user.
     *
     * @param user      The name of the user to authenticate the connection with.
     * @param password  The password of the user
     */
    AbstractLdapConnection open(String user, SecuredString password) ;

}
