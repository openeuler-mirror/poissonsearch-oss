/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.watcher;

import com.google.common.collect.ImmutableList;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.ClusterModule;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.settings.Validator;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.watcher.actions.email.service.InternalEmailService;
import org.elasticsearch.watcher.history.HistoryModule;
import org.elasticsearch.watcher.license.LicenseService;
import org.elasticsearch.watcher.support.WatcherIndexTemplateRegistry.TemplateConfig;
import org.elasticsearch.watcher.support.http.HttpClient;
import org.elasticsearch.watcher.support.init.InitializingService;
import org.elasticsearch.watcher.support.init.proxy.ScriptServiceProxy;
import org.elasticsearch.watcher.support.validation.WatcherSettingsValidation;

import java.util.Collection;
import java.util.Collections;

import static org.elasticsearch.common.settings.Settings.settingsBuilder;

public class WatcherPlugin extends Plugin {

    public static final String NAME = "watcher";
    public static final String ENABLED_SETTING = NAME + ".enabled";

    static {
        MetaData.registerPrototype(WatcherMetaData.TYPE, WatcherMetaData.PROTO);
    }

    protected final Settings settings;
    protected final boolean transportClient;
    protected final boolean enabled;

    public WatcherPlugin(Settings settings) {
        this.settings = settings;
        transportClient = "transport".equals(settings.get(Client.CLIENT_TYPE_SETTING));
        enabled = watcherEnabled(settings);
    }

    @Override public String name() {
        return NAME;
    }

    @Override public String description() {
        return "Elasticsearch Watcher";
    }

    @Override
    public Collection<Module> nodeModules() {
        if (!enabled) {
            return ImmutableList.of();
        }
        return transportClient ?
                Collections.<Module>singletonList(new TransportClientWatcherModule()) :
                Collections.<Module>singletonList(new WatcherModule(settings));
    }


    @Override
    public Collection<Class<? extends LifecycleComponent>> nodeServices() {
        if (!enabled || transportClient) {
            return ImmutableList.of();
        }
        return ImmutableList.<Class<? extends LifecycleComponent>>of(
                // the initialization service must be first in the list
                // as other services may depend on one of the initialized
                // constructs
                InitializingService.class,
                LicenseService.class,
                InternalEmailService.class,
                HttpClient.class,
                WatcherSettingsValidation.class);
    }

    @Override
    public Settings additionalSettings() {
        if (!enabled || transportClient) {
            return Settings.EMPTY;
        }
        Settings additionalSettings = settingsBuilder()
                .put(HistoryModule.additionalSettings(settings))
                .build();

        return additionalSettings;
    }

    public void onModule(ScriptModule module) {
        module.registerScriptContext(ScriptServiceProxy.INSTANCE);
    }

    public void onModule(ClusterModule module) {
        for (TemplateConfig templateConfig : WatcherModule.TEMPLATE_CONFIGS) {
            module.registerClusterDynamicSetting(templateConfig.getDynamicSettingsPrefix(), Validator.EMPTY);
        }
    }

    public static boolean watcherEnabled(Settings settings) {
        return settings.getAsBoolean(ENABLED_SETTING, true);
    }

}
