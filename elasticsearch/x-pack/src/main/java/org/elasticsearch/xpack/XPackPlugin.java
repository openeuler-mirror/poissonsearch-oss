/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack;

import org.elasticsearch.SpecialPermission;
import org.elasticsearch.action.ActionModule;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.env.Environment;
import org.elasticsearch.graph.Graph;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.license.plugin.Licensing;
import org.elasticsearch.marvel.Marvel;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.shield.Shield;
import org.elasticsearch.shield.authc.AuthenticationModule;
import org.elasticsearch.watcher.Watcher;
import org.elasticsearch.xpack.common.init.LazyInitializationModule;
import org.elasticsearch.xpack.common.init.LazyInitializationService;
import org.elasticsearch.xpack.extensions.XPackExtension;
import org.elasticsearch.xpack.extensions.XPackExtensionsService;

import java.nio.file.Path;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

public class XPackPlugin extends Plugin {

    public static final String NAME = "xpack";

    // TODO: clean up this library to not ask for write access to all system properties!
    static {
        // invoke this clinit in unbound with permissions to access all system properties
        SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(new SpecialPermission());
        }
        try {
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                @Override
                public Void run() {
                    try {
                        Class.forName("com.unboundid.util.Debug");
                    } catch (ClassNotFoundException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                }
            });
            // TODO: fix gradle to add all shield resources (plugin metadata) to test classpath
            // of watcher plugin, which depends on it directly. This prevents these plugins
            // from being initialized correctly by the test framework, and means we have to
            // have this leniency.
        } catch (ExceptionInInitializerError bogus) {
            if (bogus.getCause() instanceof SecurityException == false) {
                throw bogus; // some other bug
            }
        }
    }

    protected final Settings settings;
    protected final XPackExtensionsService extensionsService;

    protected Licensing licensing;
    protected Shield shield;
    protected Marvel marvel;
    protected Watcher watcher;
    protected Graph graph;

    public XPackPlugin(Settings settings) {
        this.settings = settings;
        this.licensing = new Licensing(settings);
        this.shield = new Shield(settings);
        this.marvel = new Marvel(settings);
        this.watcher = new Watcher(settings);
        this.graph = new Graph(settings);
        // Check if the node is a transport client.
        if (transportClientMode(settings) == false) {
            Environment env = new Environment(settings);
            this.extensionsService =
                    new XPackExtensionsService(settings, resolveXPackExtensionsFile(env), getExtensions());
        } else {
            this.extensionsService = null;
        }
    }

    @Override public String name() {
        return NAME;
    }

    @Override public String description() {
        return "Elastic X-Pack";
    }

    // For tests only
    public Collection<Class<? extends XPackExtension>> getExtensions() {
        return Collections.emptyList();
    }

    @Override
    public Collection<Module> nodeModules() {
        ArrayList<Module> modules = new ArrayList<>();
        modules.add(new LazyInitializationModule());
        modules.addAll(licensing.nodeModules());
        modules.addAll(shield.nodeModules());
        modules.addAll(watcher.nodeModules());
        modules.addAll(marvel.nodeModules());
        modules.addAll(graph.nodeModules());
        return modules;
    }

    @Override
    public Collection<Class<? extends LifecycleComponent>> nodeServices() {
        ArrayList<Class<? extends LifecycleComponent>> services = new ArrayList<>();
        // the initialization service must be first in the list
        // as other services may depend on one of the initialized
        // constructs
        services.add(LazyInitializationService.class);
        services.addAll(licensing.nodeServices());
        services.addAll(shield.nodeServices());
        services.addAll(watcher.nodeServices());
        services.addAll(marvel.nodeServices());
        services.addAll(graph.nodeServices());
        return services;
    }

    @Override
    public Settings additionalSettings() {
        Settings.Builder builder = Settings.builder();
        builder.put(shield.additionalSettings());
        builder.put(watcher.additionalSettings());
        builder.put(graph.additionalSettings());
        return builder.build();
    }

    public void onModule(ScriptModule module) {
        watcher.onModule(module);
    }

    public void onModule(SettingsModule module) {

        // we add the `xpack.version` setting to all internal indices
        module.registerSetting(Setting.simpleString("index.xpack.version", Setting.Property.IndexScope));

        shield.onModule(module);
        marvel.onModule(module);
        watcher.onModule(module);
        graph.onModule(module);
        licensing.onModule(module);
    }

    public void onModule(NetworkModule module) {
        licensing.onModule(module);
        shield.onModule(module);
        watcher.onModule(module);
        graph.onModule(module);
    }

    public void onModule(ActionModule module) {
        licensing.onModule(module);
        shield.onModule(module);
        watcher.onModule(module);
        graph.onModule(module);
    }

    public void onModule(AuthenticationModule module) {
        if (extensionsService != null) {
            extensionsService.onModule(module);
        }
    }

    public void onIndexModule(IndexModule module) {
        shield.onIndexModule(module);
        graph.onIndexModule(module);
    }

    public void onModule(LazyInitializationModule module) {
        watcher.onModule(module);
    }

    public static boolean transportClientMode(Settings settings) {
        return !"node".equals(settings.get(Client.CLIENT_TYPE_SETTING_S.getKey()));
    }

    public static boolean isTribeNode(Settings settings) {
        return settings.getGroups("tribe", true).isEmpty() == false;
    }
    public static boolean isTribeClientNode(Settings settings) {
        return settings.get("tribe.name") != null;
    }

    public static Path resolveConfigFile(Environment env, String name) {
        return env.configFile().resolve(NAME).resolve(name);
    }

    /**
     * A consistent way to enable disable features using the following setting:
     *
     *          {@code "xpack.<feature>.enabled": true | false}
     *
     *  Also supports the following setting as a fallback (for BWC with 1.x/2.x):
     *
     *          {@code "<feature>.enabled": true | false}
     */
    public static boolean featureEnabled(Settings settings, String featureName, boolean defaultValue) {
        return settings.getAsBoolean(featureEnabledSetting(featureName),
                settings.getAsBoolean(legacyFeatureEnabledSetting(featureName), defaultValue)); // for bwc
    }

    public static String featureEnabledSetting(String featureName) {
        return featureSettingPrefix(featureName) + ".enabled";
    }

    public static String featureSettingPrefix(String featureName) {
        return NAME + "." + featureName;
    }

    public static String legacyFeatureEnabledSetting(String featureName) {
        return featureName + ".enabled";
    }

    /**
     * A consistent way to register the settings used to enable disable features, supporting the following format:
     *
     *          {@code "xpack.<feature>.enabled": true | false}
     *
     *  Also supports the following setting as a fallback (for BWC with 1.x/2.x):
     *
     *          {@code "<feature>.enabled": true | false}
     */
    public static void registerFeatureEnabledSettings(SettingsModule settingsModule, String featureName, boolean defaultValue) {
        settingsModule.registerSetting(Setting.boolSetting(featureEnabledSetting(featureName), defaultValue, Setting.Property.NodeScope));
        settingsModule.registerSetting(Setting.boolSetting(legacyFeatureEnabledSetting(featureName),
                defaultValue, Setting.Property.NodeScope));
    }

    public static Path resolveXPackExtensionsFile(Environment env) {
        return env.pluginsFile().resolve("xpack").resolve("extensions");
    }
}
