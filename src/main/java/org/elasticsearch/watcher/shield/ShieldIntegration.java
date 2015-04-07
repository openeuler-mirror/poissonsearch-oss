/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher.shield;

import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Injector;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.shield.ShieldPlugin;
import org.elasticsearch.shield.ShieldVersion;
import org.elasticsearch.shield.authc.AuthenticationService;
import org.elasticsearch.transport.TransportMessage;
import org.elasticsearch.watcher.WatcherVersion;

/**
 *
 */
public class ShieldIntegration {

    private static final int minCompatibleShieldVersionId = /*00*/1020199; // V_1_2_1

    private final boolean installed;
    private final boolean enabled;
    private final Object authcService;
    private final Object userHolder;

    @Inject
    public ShieldIntegration(Settings settings, Injector injector) {
        installed = installed(settings);
        enabled = installed && ShieldPlugin.shieldEnabled(settings);
        authcService = enabled ? injector.getInstance(AuthenticationService.class) : null;
        userHolder = enabled ? injector.getInstance(WatcherUserHolder.class) : null;
    }

    public boolean installed() {
        return installed;
    }

    public boolean enabled() {
        return enabled;
    }

    public void bindWatcherUser(TransportMessage message) {
        if (authcService != null) {
            ((AuthenticationService) authcService).attachUserHeaderIfMissing(message, ((WatcherUserHolder) userHolder).user);
        }
    }

    static boolean installed(Settings settings) {
        try {
            Class clazz = settings.getClassLoader().loadClass("org.elasticsearch.shield.ShieldPlugin");
            if (clazz == null) {
                return false;
            }

            // lets check min compatibility
            ShieldVersion minVersion = ShieldVersion.fromId(minCompatibleShieldVersionId);
            if (!ShieldVersion.CURRENT.onOrAfter(minVersion)) {
                throw new ElasticsearchIllegalStateException("watcher [" + WatcherVersion.CURRENT + "] requires " +
                        "minimum shield plugin version [" + minVersion + "], but installed shield plugin version is " +
                        "[" + ShieldVersion.CURRENT + "]");
            }

            return true;

        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static boolean enabled(Settings settings) {
        return installed(settings) && ShieldPlugin.shieldEnabled(settings);
    }

}
