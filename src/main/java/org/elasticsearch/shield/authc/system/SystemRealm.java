/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.system;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.authc.AuthenticationToken;
import org.elasticsearch.shield.authc.Realm;
import org.elasticsearch.shield.support.AbstractShieldModule;
import org.elasticsearch.transport.TransportMessage;

/**
 *
 */
public class SystemRealm implements Realm<AuthenticationToken> {

    public static final AuthenticationToken TOKEN = new AuthenticationToken() {
        @Override
        public String principal() {
            return "_system";
        }

        @Override
        public Object credentials() {
            return null;
        }

        @Override
        public void clearCredentials() {

        }
    };

    @Override
    public String type() {
        return "system";
    }

    @Override
    public AuthenticationToken token(RestRequest request) {
        return null; // system token can never come from the rest API
    }

    @Override
    public AuthenticationToken token(TransportMessage<?> message) {
        // as far as this realm is concerned, there's never a system token
        // in the request. The decision of whether a request is a system
        // request or not, is made elsewhere where the system token is
        // assumed
        return null;
    }

    @Override
    public boolean supports(AuthenticationToken token) {
        return token == TOKEN;
    }

    @Override
    public User authenticate(AuthenticationToken token) {
        return token == TOKEN ? User.SYSTEM : null;
    }

    public static class Module extends AbstractShieldModule.Node {

        public Module(Settings settings) {
            super(settings);
        }

        @Override
        protected void configureNode() {
            bind(SystemRealm.class).asEagerSingleton();
        }
    }
}
