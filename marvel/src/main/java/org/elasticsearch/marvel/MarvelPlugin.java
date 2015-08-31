/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel;

import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.settings.Validator;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.marvel.agent.AgentService;
import org.elasticsearch.marvel.agent.collector.CollectorModule;
import org.elasticsearch.marvel.agent.exporter.ExporterModule;
import org.elasticsearch.marvel.agent.exporter.HttpESExporter;
import org.elasticsearch.marvel.agent.renderer.RendererModule;
import org.elasticsearch.marvel.agent.settings.MarvelModule;
import org.elasticsearch.marvel.agent.settings.MarvelSetting;
import org.elasticsearch.marvel.agent.settings.MarvelSettings;
import org.elasticsearch.marvel.license.LicenseModule;
import org.elasticsearch.marvel.license.LicenseService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.tribe.TribeService;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

public class MarvelPlugin extends Plugin {

    private static final ESLogger logger = Loggers.getLogger(MarvelPlugin.class);

    public static final String NAME = "marvel";
    public static final String ENABLED = NAME + ".enabled";

    private final boolean enabled;

    public MarvelPlugin(Settings settings) {
        this.enabled = marvelEnabled(settings);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Elasticsearch Marvel";
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public Collection<Module> nodeModules() {
        if (!enabled) {
            return Collections.emptyList();
        }
        return Arrays.<Module>asList(
            new MarvelModule(),
            new LicenseModule(),
            new CollectorModule(),
            new ExporterModule(),
            new RendererModule());
    }

    @Override
    public Collection<Class<? extends LifecycleComponent>> nodeServices() {
        if (!enabled) {
            return Collections.emptyList();
        }
        return Arrays.<Class<? extends LifecycleComponent>>asList(LicenseService.class, AgentService.class);
    }

    public static boolean marvelEnabled(Settings settings) {
        String tribe = settings.get(TribeService.TRIBE_NAME);
        if (tribe != null) {
            logger.trace("marvel cannot be started on tribe node [{}]", tribe);
            return false;
        }

        if (!"node".equals(settings.get(Client.CLIENT_TYPE_SETTING))) {
            logger.trace("marvel cannot be started on a transport client");
            return false;
        }
        return settings.getAsBoolean(ENABLED, true);
    }

    public void onModule(ClusterModule module) {
        // HttpESExporter
        module.registerClusterDynamicSetting(HttpESExporter.SETTINGS_HOSTS, Validator.EMPTY);
        module.registerClusterDynamicSetting(HttpESExporter.SETTINGS_HOSTS + ".*", Validator.EMPTY);
        module.registerClusterDynamicSetting(HttpESExporter.SETTINGS_TIMEOUT, Validator.EMPTY);
        module.registerClusterDynamicSetting(HttpESExporter.SETTINGS_READ_TIMEOUT, Validator.EMPTY);
        module.registerClusterDynamicSetting(HttpESExporter.SETTINGS_SSL_HOSTNAME_VERIFICATION, Validator.EMPTY);

        // MarvelSettingsService
        for (MarvelSetting setting : MarvelSettings.dynamicSettings()) {
            module.registerClusterDynamicSetting(setting.dynamicSettingName(), setting.dynamicValidator());
        }
    }
}
