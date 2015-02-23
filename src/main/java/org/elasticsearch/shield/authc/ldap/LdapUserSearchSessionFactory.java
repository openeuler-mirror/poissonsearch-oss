/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.ldap;

import com.unboundid.ldap.sdk.*;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.primitives.Ints;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.shield.ShieldSettingsException;
import org.elasticsearch.shield.authc.RealmConfig;
import org.elasticsearch.shield.authc.ldap.support.LdapSearchScope;
import org.elasticsearch.shield.authc.ldap.support.LdapSession;
import org.elasticsearch.shield.authc.ldap.support.LdapSession.GroupsResolver;
import org.elasticsearch.shield.authc.ldap.support.SessionFactory;
import org.elasticsearch.shield.authc.support.SecuredString;
import org.elasticsearch.shield.ssl.ClientSSLService;

import javax.net.SocketFactory;

import java.util.Locale;

import static com.unboundid.ldap.sdk.Filter.createEqualityFilter;
import static com.unboundid.ldap.sdk.Filter.encodeValue;
import static org.elasticsearch.shield.authc.ldap.support.LdapUtils.searchForEntry;

public class LdapUserSearchSessionFactory extends SessionFactory {

    static final int DEFAULT_CONNECTION_POOL_SIZE = 20;
    static final int DEFAULT_CONNECTION_POOL_INITIAL_SIZE = 5;
    static final String DEFAULT_USERNAME_ATTRIBUTE = "uid";
    static final TimeValue DEFAULT_HEALTH_CHECK_INTERVAL = TimeValue.timeValueSeconds(60L);

    private final GroupsResolver groupResolver;
    private final LDAPConnectionPool connectionPool;
    private final String userSearchBaseDn;
    private final LdapSearchScope scope;
    private final String userAttribute;
    private final ServerSet serverSet;

    public LdapUserSearchSessionFactory(RealmConfig config, ClientSSLService sslService) {
        super(config);
        Settings settings = config.settings();
        userSearchBaseDn = settings.get("user_search.base_dn");
        if (userSearchBaseDn == null) {
            throw new ShieldSettingsException("user_search base_dn must be specified");
        }
        scope = LdapSearchScope.resolve(settings.get("user_search.scope"), LdapSearchScope.SUB_TREE);
        userAttribute = settings.get("user_search.attribute", DEFAULT_USERNAME_ATTRIBUTE);
        serverSet = serverSet(settings, sslService);
        connectionPool = connectionPool(config.settings(), serverSet, timeout);
        groupResolver = groupResolver(settings);
    }

    static LDAPConnectionPool connectionPool(Settings settings, ServerSet serverSet, TimeValue timeout) {
        SimpleBindRequest bindRequest = bindRequest(settings);
        int initialSize = settings.getAsInt("user_search.pool.initial_size", DEFAULT_CONNECTION_POOL_INITIAL_SIZE);
        int size = settings.getAsInt("user_search.pool.size", DEFAULT_CONNECTION_POOL_SIZE);
        try {
            LDAPConnectionPool pool = new LDAPConnectionPool(serverSet, bindRequest, initialSize, size);
            pool.setRetryFailedOperationsDueToInvalidConnections(true);
            if (settings.getAsBoolean("user_search.pool.health_check.enabled", true)) {
                String entryDn = settings.get("user_search.pool.health_check.dn", (bindRequest == null) ? null : bindRequest.getBindDN());
                if (entryDn == null) {
                    pool.close();
                    throw new ShieldSettingsException("[user_search.bind_dn] has not been specified so a value must be specified for [user_search.pool.health_check.dn] or [user_search.pool.health_check.enabled] must be set to false");
                }
                long healthCheckInterval = settings.getAsTime("user_search.pool.health_check.interval", DEFAULT_HEALTH_CHECK_INTERVAL).millis();
                // Checks the status of the LDAP connection at a specified interval in the background. We do not check on
                // on create as the LDAP server may require authentication to get an entry. We do not check on checkout
                // as we always set retry operations and the pool will handle a bad connection without the added latency on every operation
                GetEntryLDAPConnectionPoolHealthCheck healthCheck = new GetEntryLDAPConnectionPoolHealthCheck(entryDn, timeout.millis(), false, false, false, true, false);
                pool.setHealthCheck(healthCheck);
                pool.setHealthCheckIntervalMillis(healthCheckInterval);
            }
            return pool;
        } catch (LDAPException e) {
            throw new ShieldLdapException("unable to connect to any LDAP servers", e);
        }
    }

    static SimpleBindRequest bindRequest(Settings settings) {
        SimpleBindRequest request = null;
        String bindDn = settings.get("user_search.bind_dn");
        if (bindDn != null) {
            request = new SimpleBindRequest(bindDn, settings.get("user_search.bind_password"));
        }
        return request;
    }

    ServerSet serverSet(Settings settings, ClientSSLService clientSSLService) {
        // Parse LDAP urls
        String[] ldapUrls = settings.getAsArray(URLS_SETTING);
        if (ldapUrls == null || ldapUrls.length == 0) {
            throw new ShieldSettingsException("missing required LDAP setting [" + URLS_SETTING + "]");
        }
        LDAPServers servers = new LDAPServers(ldapUrls);
        LDAPConnectionOptions options = connectionOptions(settings);
        SocketFactory socketFactory;
        if (servers.ssl()) {
            socketFactory = clientSSLService.sslSocketFactory();
            if (settings.getAsBoolean(HOSTNAME_VERIFICATION_SETTING, true)) {
                logger.debug("using encryption for LDAP connections with hostname verification");
            } else {
                logger.debug("using encryption for LDAP connections without hostname verification");
            }
        } else {
            socketFactory = null;
        }
        FailoverServerSet serverSet = new FailoverServerSet(servers.addresses(), servers.ports(), socketFactory, options);
        serverSet.setReOrderOnFailover(true);
        return serverSet;
    }

    @Override
    public LdapSession session(String user, SecuredString password) {
        SearchRequest request = new SearchRequest(userSearchBaseDn, scope.scope(), createEqualityFilter(userAttribute, encodeValue(user)), Strings.EMPTY_ARRAY);
        request.setTimeLimitSeconds(Ints.checkedCast(timeout.seconds()));
        try {
            SearchResultEntry entry = searchForEntry(connectionPool, request, logger);
            if (entry == null) {
                throw new ShieldLdapException("failed to find user [" + user + "] with search base [" + userSearchBaseDn + "] scope [" + scope.toString().toLowerCase(Locale.ENGLISH) +"]");
            }
            String dn = entry.getDN();
            tryBind(dn, password);
            return new LdapSession(logger, connectionPool, dn, groupResolver, timeout);
        } catch (LDAPException e) {
            throw new ShieldLdapException("failed to authenticate user [" + user + "]", e);
        }
    }

    private void tryBind(String dn, SecuredString password) {
        LDAPConnection bindConnection;
        try {
            bindConnection = serverSet.getConnection();
        } catch (LDAPException e) {
            throw new ShieldLdapException("unable to connect to any LDAP servers for bind", e);
        }

        try {
            bindConnection.bind(dn, new String(password.internalChars()));
        } catch (LDAPException e) {
            throw new ShieldLdapException("failed LDAP authentication", dn, e);
        } finally {
            bindConnection.close();
        }
    }

    /*
     * This method is used to cleanup the connections for tests
     */
    void shutdown() {
        connectionPool.close();
    }

    static GroupsResolver groupResolver(Settings settings) {
        Settings searchSettings = settings.getAsSettings("group_search");
        if (!searchSettings.names().isEmpty()) {
            return new SearchGroupsResolver(searchSettings);
        }
        return new UserAttributeGroupsResolver(settings);
    }
}
