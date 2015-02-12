/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc.ldap;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.shield.ShieldSettingsException;
import org.elasticsearch.shield.authc.RealmConfig;
import org.elasticsearch.shield.authc.ldap.support.AbstractLdapRealm;
import org.elasticsearch.shield.authc.ldap.support.GroupToRoleMapper;
import org.elasticsearch.shield.authc.ldap.support.SessionFactory;
import org.elasticsearch.shield.ssl.ClientSSLService;
import org.elasticsearch.watcher.ResourceWatcherService;

/**
 * Authenticates username/password tokens against ldap, locates groups and maps them to roles.
 */
public class LdapRealm extends AbstractLdapRealm {

    public static final String TYPE = "ldap";

    public LdapRealm(RealmConfig config, SessionFactory ldap, GroupToRoleMapper roleMapper) {
        super(TYPE, config, ldap, roleMapper);
    }

    public static class Factory extends AbstractLdapRealm.Factory<LdapRealm> {

        private final ResourceWatcherService watcherService;
        private final ClientSSLService clientSSLService;

        @Inject
        public Factory(ResourceWatcherService watcherService, RestController restController, ClientSSLService clientSSLService) {
            super(TYPE, restController);
            this.watcherService = watcherService;
            this.clientSSLService = clientSSLService;
        }

        @Override
        public LdapRealm create(RealmConfig config) {
            SessionFactory sessionFactory = sessionFactory(config, clientSSLService);
            GroupToRoleMapper roleMapper = new GroupToRoleMapper(TYPE, config, watcherService, null);
            return new LdapRealm(config, sessionFactory, roleMapper);
        }

        static SessionFactory sessionFactory(RealmConfig config, ClientSSLService clientSSLService) {
            Settings searchSettings = config.settings().getAsSettings("user_search");
            if (!searchSettings.names().isEmpty()) {
                if (config.settings().getAsArray(LdapSessionFactory.USER_DN_TEMPLATES_SETTING).length > 0) {
                    throw new ShieldSettingsException("settings were found for both user search and user template modes of operation. Please remove the settings for the\n"
                            + "mode you do not wish to use. For more details refer to the ldap authentication section of the Shield guide.");
                }
                return new LdapUserSearchSessionFactory(config, clientSSLService);
            }
            return new LdapSessionFactory(config, clientSSLService);
        }
    }
}
