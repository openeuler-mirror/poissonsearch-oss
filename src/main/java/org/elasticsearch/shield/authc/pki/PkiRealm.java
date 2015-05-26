/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.pki;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.shield.ShieldSettingsException;
import org.elasticsearch.shield.ShieldSettingsFilter;
import org.elasticsearch.shield.User;
import org.elasticsearch.shield.authc.AuthenticationToken;
import org.elasticsearch.shield.authc.Realm;
import org.elasticsearch.shield.authc.RealmConfig;
import org.elasticsearch.shield.authc.support.DnRoleMapper;
import org.elasticsearch.shield.transport.netty.ShieldNettyHttpServerTransport;
import org.elasticsearch.shield.transport.netty.ShieldNettyTransport;
import org.elasticsearch.transport.TransportMessage;
import org.elasticsearch.watcher.ResourceWatcherService;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PkiRealm extends Realm<X509AuthenticationToken> {

    public static final String PKI_CERT_HEADER_NAME = "__SHIELD_CLIENT_CERTIFICATE";
    public static final String TYPE = "pki";

    // For client based cert validation, the auth type must be specified but UNKNOWN is an acceptable value
    public static final String AUTH_TYPE = "UNKNOWN";

    private final X509TrustManager[] trustManagers;
    private final Pattern principalPattern;
    private final DnRoleMapper roleMapper;

    public PkiRealm(RealmConfig config, DnRoleMapper roleMapper) {
        super(TYPE, config);
        this.trustManagers = trustManagers(config.settings(), config.env());
        this.principalPattern = Pattern.compile(config.settings().get("username_pattern", "CN=(.*?),"), Pattern.CASE_INSENSITIVE);
        this.roleMapper = roleMapper;
        checkSSLEnabled(config, logger);
    }

    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof X509AuthenticationToken;
    }

    @Override
    public X509AuthenticationToken token(RestRequest request) {
        return token(request.getFromContext(PKI_CERT_HEADER_NAME), principalPattern, logger);
    }

    @Override
    public X509AuthenticationToken token(TransportMessage<?> message) {
        return token(message.getFromContext(PKI_CERT_HEADER_NAME), principalPattern, logger);
    }

    @Override
    public User authenticate(X509AuthenticationToken token) {
        if (!isCertificateChainTrusted(trustManagers, token, logger)) {
            return null;
        }

        Set<String> roles = roleMapper.resolveRoles(token.dn(), Collections.<String>emptyList());
        return new User.Simple(token.principal(), roles.toArray(new String[roles.size()]));
    }

    static X509AuthenticationToken token(Object pkiHeaderValue, Pattern principalPattern, ESLogger logger) {
        if (pkiHeaderValue == null) {
            return null;
        }

        assert pkiHeaderValue instanceof X509Certificate[];
        X509Certificate[] certificates = (X509Certificate[]) pkiHeaderValue;
        if (certificates.length == 0) {
            return null;
        }

        String dn = certificates[0].getSubjectX500Principal().getName();
        Matcher matcher = principalPattern.matcher(dn);
        if (!matcher.find()) {
            if (logger.isDebugEnabled()) {
                logger.debug("certificate authentication succeeded for [{}] but could not extract principal from DN", dn);
            }
            return null;
        }

        String principal = matcher.group(1);
        return new X509AuthenticationToken(certificates, principal, dn);
    }

    static boolean isCertificateChainTrusted(X509TrustManager[] trustManagers, X509AuthenticationToken token, ESLogger logger) {
        if (trustManagers.length > 0) {
            boolean trusted = false;
            for (X509TrustManager trustManager : trustManagers) {
                try {
                    trustManager.checkClientTrusted(token.credentials(), AUTH_TYPE);
                    trusted = true;
                    break;
                } catch (CertificateException e) {
                    if (logger.isTraceEnabled()) {
                        logger.trace("failed certificate validation for principal [{}]", e, token.principal());
                    } else if (logger.isDebugEnabled()) {
                        logger.debug("failed certificate validation for principal [{}]", token.principal());
                    }
                }
            }

            return trusted;
        }

        // No extra trust managers specified, so at this point we can be considered authenticated.
        return true;
    }

    static X509TrustManager[] trustManagers(Settings settings, Environment env) {
        String truststorePath = settings.get("truststore.path");
        if (truststorePath == null) {
            return new X509TrustManager[0];
        }

        String password = settings.get("truststore.password");
        if (password == null) {
            throw new ShieldSettingsException("no truststore password configured");
        }

        String trustStoreAlgorithm = settings.get("truststore.algorithm", System.getProperty("ssl.TrustManagerFactory.algorithm", TrustManagerFactory.getDefaultAlgorithm()));
        TrustManager[] trustManagers;
        try (InputStream in = Files.newInputStream(env.homeFile().resolve(truststorePath))) {
            // Load TrustStore
            KeyStore ks = KeyStore.getInstance("jks");
            ks.load(in, password.toCharArray());

            // Initialize a trust manager factory with the trusted store
            TrustManagerFactory trustFactory = TrustManagerFactory.getInstance(trustStoreAlgorithm);
            trustFactory.init(ks);
            trustManagers = trustFactory.getTrustManagers();
        } catch (Exception e) {
            throw new ShieldSettingsException("failed to load specified truststore", e);
        }

        List<X509TrustManager> trustManagerList = new ArrayList<>();
        for (TrustManager trustManager : trustManagers) {
            if (trustManager instanceof X509TrustManager) {
                trustManagerList.add((X509TrustManager) trustManager);
            }
        }

        if (trustManagerList.isEmpty()) {
            throw new ShieldSettingsException("no valid certificates found in truststore");
        }

        return trustManagerList.toArray(new X509TrustManager[trustManagerList.size()]);
    }

    static void filterOutSensitiveSettings(String realmName, ShieldSettingsFilter filter) {
        filter.filterOut("shield.authc.realms." + realmName + "." + "truststore.password");
        filter.filterOut("shield.authc.realms." + realmName + "." + "truststore.path");
        filter.filterOut("shield.authc.realms." + realmName + "." + "truststore.algorithm");
    }

    /**
     * Checks to see if both SSL and Client authentication are enabled on at least one network communication layer. If
     * not an error message will be logged
     * @param config
     */
    static void checkSSLEnabled(RealmConfig config, ESLogger logger) {
        Settings settings = config.globalSettings();

        // HTTP
        if (settings.getAsBoolean(ShieldNettyHttpServerTransport.HTTP_SSL_SETTING, ShieldNettyHttpServerTransport.HTTP_SSL_DEFAULT)
                && settings.getAsBoolean(ShieldNettyHttpServerTransport.HTTP_CLIENT_AUTH_SETTING, ShieldNettyHttpServerTransport.HTTP_CLIENT_AUTH_DEFAULT)) {
            return;
        }

        // Default Transport
        final boolean ssl = settings.getAsBoolean(ShieldNettyTransport.TRANSPORT_SSL_SETTING, ShieldNettyTransport.TRANSPORT_SSL_DEFAULT);
        final boolean clientAuth = settings.getAsBoolean(ShieldNettyTransport.TRANSPORT_CLIENT_AUTH_SETTING, ShieldNettyTransport.TRANSPORT_CLIENT_AUTH_DEFAULT);
        if (ssl && clientAuth) {
            return;
        }

        // Transport Profiles
        Map<String, Settings> groupedSettings = settings.getGroups("transport.profiles.");
        for (Map.Entry<String, Settings> entry : groupedSettings.entrySet()) {
            Settings profileSettings = entry.getValue().getByPrefix("shield.filter.");
            if (profileSettings.getAsBoolean(ShieldNettyTransport.TRANSPORT_PROFILE_SSL_SETTING, ssl)
                    && profileSettings.getAsBoolean(ShieldNettyTransport.TRANSPORT_CLIENT_AUTH_SETTING, clientAuth)) {
                return;
            }
        }

        logger.error("PKI realm [{}] is enabled but cannot be used as neither HTTP or Transport have both SSL and client authentication enabled", config.name());
    }

    public static class Factory extends Realm.Factory<PkiRealm> {

        private final ResourceWatcherService watcherService;

        @Inject
        public Factory(ResourceWatcherService watcherService) {
            super(TYPE, false);
            this.watcherService = watcherService;
        }

        @Override
        public void filterOutSensitiveSettings(String realmName, ShieldSettingsFilter filter) {
            PkiRealm.filterOutSensitiveSettings(realmName, filter);
        }

        @Override
        public PkiRealm create(RealmConfig config) {
            DnRoleMapper roleMapper = new DnRoleMapper(TYPE, config, watcherService, null);
            return new PkiRealm(config, roleMapper);
        }

        @Override
        public PkiRealm createDefault(String name) {
            return null;
        }
    }
}
