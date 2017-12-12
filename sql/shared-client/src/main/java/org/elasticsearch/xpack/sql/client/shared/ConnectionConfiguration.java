/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.sql.client.shared;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static java.util.Collections.emptyList;

/**
 * Common configuration class used for client.
 * Uses a Properties object to be created (as clients would use strings to configure it).
 * While this is convenient, it makes validation tricky (of both the names and values) and thus
 * it's available only during construction.
 * Some values might be updated later on in a typed fashion (dedicated method) in order
 * to move away from the loose Strings...
 */
public class ConnectionConfiguration {

    // Timeouts

    // 30s
    public static final String CONNECT_TIMEOUT = "connect.timeout";
    private static final String CONNECT_TIMEOUT_DEFAULT = String.valueOf(TimeUnit.SECONDS.toMillis(30));

    // 1m
    public static final String NETWORK_TIMEOUT = "network.timeout";
    private static final String NETWORK_TIMEOUT_DEFAULT = String.valueOf(TimeUnit.MINUTES.toMillis(1));

    // 90s
    public static final String QUERY_TIMEOUT = "query.timeout";
    private static final String QUERY_TIMEOUT_DEFAULT = String.valueOf(TimeUnit.SECONDS.toMillis(90));

    // 45s
    public static final String PAGE_TIMEOUT = "page.timeout";
    private static final String PAGE_TIMEOUT_DEFAULT = String.valueOf(TimeUnit.SECONDS.toMillis(45));

    public static final String PAGE_SIZE = "page.size";
    private static final String PAGE_SIZE_DEFAULT = "1000";

    // Auth

    public static final String AUTH_USER = "user";
    // NB: this is password instead of pass since that's what JDBC DriverManager/tools use
    public static final String AUTH_PASS = "password";

    protected static final Set<String> OPTION_NAMES = new LinkedHashSet<>(
            Arrays.asList(CONNECT_TIMEOUT, NETWORK_TIMEOUT, QUERY_TIMEOUT, PAGE_TIMEOUT, PAGE_SIZE, AUTH_USER, AUTH_PASS));

    static {
        OPTION_NAMES.addAll(SslConfig.OPTION_NAMES);
        OPTION_NAMES.addAll(ProxyConfig.OPTION_NAMES);
    }

    // Base URI for all request
    private final URI baseURI;
    private final String connectionString;
    // Proxy

    private long connectTimeout;
    private long networkTimeout;
    private long queryTimeout;

    private long pageTimeout;
    private int pageSize;

    private final String user, pass;

    private final SslConfig sslConfig;
    private final ProxyConfig proxyConfig;

    public ConnectionConfiguration(URI baseURI, String connectionString, Properties props) throws ClientException {
        this.connectionString = connectionString;
        Properties settings = props != null ? props : new Properties();

        checkPropertyNames(settings, optionNames());

        connectTimeout = parseValue(CONNECT_TIMEOUT, settings.getProperty(CONNECT_TIMEOUT, CONNECT_TIMEOUT_DEFAULT), Long::parseLong);
        networkTimeout = parseValue(NETWORK_TIMEOUT, settings.getProperty(NETWORK_TIMEOUT, NETWORK_TIMEOUT_DEFAULT), Long::parseLong);
        queryTimeout = parseValue(QUERY_TIMEOUT, settings.getProperty(QUERY_TIMEOUT, QUERY_TIMEOUT_DEFAULT), Long::parseLong);
        // page
        pageTimeout = parseValue(PAGE_TIMEOUT, settings.getProperty(PAGE_TIMEOUT, PAGE_TIMEOUT_DEFAULT), Long::parseLong);
        pageSize = parseValue(PAGE_SIZE, settings.getProperty(PAGE_SIZE, PAGE_SIZE_DEFAULT), Integer::parseInt);

        // auth
        user = settings.getProperty(AUTH_USER);
        pass = settings.getProperty(AUTH_PASS);

        sslConfig = new SslConfig(settings);
        proxyConfig = new ProxyConfig(settings);

        this.baseURI = normalizeSchema(baseURI, connectionString, sslConfig.isEnabled());
    }

    private static URI normalizeSchema(URI uri, String connectionString, boolean isSSLEnabled)  {
        // Make sure the protocol is correct
        final String scheme;
        if (isSSLEnabled) {
            // It's ok to upgrade from http to https
            scheme = "https";
        } else {
            // Silently downgrading from https to http can cause security issues
            if ("https".equals(uri.getScheme())) {
                throw new ClientException("SSL is disabled");
            }
            scheme = "http";
        }
        try {
            return new URI(scheme, null, uri.getHost(), uri.getPort(), uri.getPath(), uri.getQuery(), uri.getFragment());
        } catch (URISyntaxException ex) {
            throw new ClientException("Cannot parse process baseURI [" + connectionString + "] " + ex.getMessage());
        }
    }

    private Collection<String> optionNames() {
        Collection<String> options = new ArrayList<>(OPTION_NAMES);
        options.addAll(extraOptions());
        return options;
    }

    protected Collection<? extends String> extraOptions() {
        return emptyList();
    }

    private static void checkPropertyNames(Properties settings, Collection<String> knownNames) throws ClientException {
        // validate specified properties to pick up typos and such
        Enumeration<?> pNames = settings.propertyNames();
        while (pNames.hasMoreElements()) {
            String message = isKnownProperty(pNames.nextElement().toString(), knownNames);
            if (message != null) {
                throw new ClientException(message);
            }
        }
    }

    private static String isKnownProperty(String propertyName, Collection<String> knownOptions) {
        if (knownOptions.contains(propertyName)) {
            return null;
        }
        return "Unknown parameter [" + propertyName + "] ; did you mean " + StringUtils.findSimiliar(propertyName, knownOptions);
    }

    protected <T> T parseValue(String key, String value, Function<String, T> parser) {
        try {
            return parser.apply(value);
        } catch (Exception ex) {
            throw new ClientException("Cannot parse property [" + key + "] with value [" + value + "]; " + ex.getMessage());
        }
    }

    protected boolean isSSLEnabled() {
        return sslConfig.isEnabled();
    }

    SslConfig sslConfig() {
        return sslConfig;
    }

    ProxyConfig proxyConfig() {
        return proxyConfig;
    }

    public void connectTimeout(long millis) {
        connectTimeout = millis;
    }

    public long connectTimeout() {
        return connectTimeout;
    }

    public void networkTimeout(long millis) {
        networkTimeout = millis;
    }

    public long networkTimeout() {
        return networkTimeout;
    }

    public void queryTimeout(long millis) {
        queryTimeout = millis;
    }

    public long queryTimeout() {
        return queryTimeout;
    }

    public long pageTimeout() {
        return pageTimeout;
    }

    public int pageSize() {
        return pageSize;
    }

    // auth
    public String authUser() {
        return user;
    }

    public String authPass() {
        return pass;
    }

    public URI baseUri() {
        return baseURI;
    }

    /**
     * Returns the original connections string
     */
    public String connectionString() {
        return connectionString;
    }

}
