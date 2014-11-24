/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.shield.User;
import org.elasticsearch.transport.TransportMessage;

/**
 * An authentication mechanism to which the default authentication {@link org.elasticsearch.shield.authc.AuthenticationService service}
 * delegates the authentication process. Different realms may be defined, each may be based on different
 * authentication mechanism supporting its own specific authentication token type.
 */
public abstract class Realm<T extends AuthenticationToken> implements Comparable<Realm> {

    protected final ESLogger logger = Loggers.getLogger(getClass());

    protected final String type;
    protected final String name;
    protected final Settings settings;
    protected final int order;

    public Realm(String type, String name, Settings settings) {
        this.type = type;
        this.name = name;
        this.settings = settings;
        this.order = settings.getAsInt("order", Integer.MAX_VALUE);
    }

    /**
     * @return  The type of this realm
     */
    public String type() {
        return type;
    }

    /**
     * @return  The name of this realm.
     */
    public String name() {
        return name;
    }

    /**
     * @return  The order of this realm within the executing realm chain.
     */
    public int order() {
        return order;
    }

    @Override
    public int compareTo(Realm other) {
        return Integer.compare(order, other.order);
    }

    /**
     * @return  {@code true} if this realm supports the given authentication token, {@code false} otherwise.
     */
    public abstract boolean supports(AuthenticationToken token);

    /**
     * Attempts to extract an authentication token from the given rest request. If an appropriate token
     * is found it's returned, otherwise {@code null} is returned.
     *
     * @param request   The rest request
     * @return          The authentication token or {@code null} if not found
     */
    public abstract T token(RestRequest request);

    /**
     * Attempts to extract an authentication token from the given transport message. If an appropriate token
     * is found it's returned, otherwise {@code null} is returned.
     *
     * @param message   The transport message
     * @return          The authentication token or {@code null} if not found
     */
    public abstract T token(TransportMessage<?> message);

    /**
     * Authenticates the given token. A successful authentication will return the User associated
     * with the given token. An unsuccessful authentication returns {@code null}.
     *
     * @param token The authentication token
     * @return      The authenticated user or {@code null} if authentication failed.
     */
    public abstract User authenticate(T token);


    /**
     * A factory for a specific realm type. Knows how to create a new realm given the appropriate
     * settings
     */
    public static abstract class Factory<R extends Realm> {

        private final String type;
        private final boolean internal;

        public Factory(String type, boolean internal) {
            this.type = type;
            this.internal = internal;
        }

        /**
         * @return  The type of the ream this factory creates
         */
        public String type() {
            return type;
        }

        public boolean internal() {
            return internal;
        }

        /**
         * Creates a new realm based on the given settigns.
         *
         * @param settings  The settings for the realm.
         * @return          The new realm (this method never returns {@code null}).
         */
        public abstract R create(String name, Settings settings);

        /**
         * Creates a default realm, one that has no custom settings. Some realms might require minimal
         * settings, in which case, this method will return {@code null}.
         */
        public abstract R createDefault(String name);
    }

}
