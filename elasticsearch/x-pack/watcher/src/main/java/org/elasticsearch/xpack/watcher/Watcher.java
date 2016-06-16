/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher;

import org.elasticsearch.action.ActionModule;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.Booleans;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.LoggerMessageFormat;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.regex.Regex;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.script.ScriptModule;
import org.elasticsearch.threadpool.ExecutorBuilder;
import org.elasticsearch.threadpool.FixedExecutorBuilder;
import org.elasticsearch.xpack.XPackPlugin;
import org.elasticsearch.xpack.common.init.LazyInitializationModule;
import org.elasticsearch.xpack.watcher.actions.WatcherActionModule;
import org.elasticsearch.xpack.watcher.client.WatcherClientModule;
import org.elasticsearch.xpack.watcher.condition.ConditionModule;
import org.elasticsearch.xpack.watcher.execution.ExecutionModule;
import org.elasticsearch.xpack.watcher.execution.InternalWatchExecutor;
import org.elasticsearch.xpack.watcher.history.HistoryModule;
import org.elasticsearch.xpack.watcher.history.HistoryStore;
import org.elasticsearch.xpack.watcher.input.InputModule;
import org.elasticsearch.xpack.watcher.input.chain.ChainInputFactory;
import org.elasticsearch.xpack.watcher.rest.action.RestAckWatchAction;
import org.elasticsearch.xpack.watcher.rest.action.RestActivateWatchAction;
import org.elasticsearch.xpack.watcher.rest.action.RestDeleteWatchAction;
import org.elasticsearch.xpack.watcher.rest.action.RestExecuteWatchAction;
import org.elasticsearch.xpack.watcher.rest.action.RestGetWatchAction;
import org.elasticsearch.xpack.watcher.rest.action.RestHijackOperationAction;
import org.elasticsearch.xpack.watcher.rest.action.RestPutWatchAction;
import org.elasticsearch.xpack.watcher.rest.action.RestWatchServiceAction;
import org.elasticsearch.xpack.watcher.rest.action.RestWatcherStatsAction;
import org.elasticsearch.xpack.common.ScriptServiceProxy;
import org.elasticsearch.xpack.watcher.support.WatcherIndexTemplateRegistry;
import org.elasticsearch.xpack.watcher.support.WatcherIndexTemplateRegistry.TemplateConfig;
import org.elasticsearch.xpack.watcher.support.init.proxy.WatcherClientProxy;
import org.elasticsearch.xpack.watcher.support.validation.WatcherSettingsValidation;
import org.elasticsearch.xpack.watcher.transform.TransformModule;
import org.elasticsearch.xpack.watcher.transform.chain.ChainTransformFactory;
import org.elasticsearch.xpack.watcher.transport.actions.ack.AckWatchAction;
import org.elasticsearch.xpack.watcher.transport.actions.ack.TransportAckWatchAction;
import org.elasticsearch.xpack.watcher.transport.actions.activate.ActivateWatchAction;
import org.elasticsearch.xpack.watcher.transport.actions.activate.TransportActivateWatchAction;
import org.elasticsearch.xpack.watcher.transport.actions.delete.DeleteWatchAction;
import org.elasticsearch.xpack.watcher.transport.actions.delete.TransportDeleteWatchAction;
import org.elasticsearch.xpack.watcher.transport.actions.execute.ExecuteWatchAction;
import org.elasticsearch.xpack.watcher.transport.actions.execute.TransportExecuteWatchAction;
import org.elasticsearch.xpack.watcher.transport.actions.get.GetWatchAction;
import org.elasticsearch.xpack.watcher.transport.actions.get.TransportGetWatchAction;
import org.elasticsearch.xpack.watcher.transport.actions.put.PutWatchAction;
import org.elasticsearch.xpack.watcher.transport.actions.put.TransportPutWatchAction;
import org.elasticsearch.xpack.watcher.transport.actions.service.TransportWatcherServiceAction;
import org.elasticsearch.xpack.watcher.transport.actions.service.WatcherServiceAction;
import org.elasticsearch.xpack.watcher.transport.actions.stats.TransportWatcherStatsAction;
import org.elasticsearch.xpack.watcher.transport.actions.stats.WatcherStatsAction;
import org.elasticsearch.xpack.watcher.trigger.TriggerModule;
import org.elasticsearch.xpack.watcher.trigger.schedule.ScheduleModule;
import org.elasticsearch.xpack.watcher.watch.WatchModule;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

public class Watcher {

    public static final String NAME = "watcher";

    public static final Setting<String> INDEX_WATCHER_VERSION_SETTING =
            new Setting<>("index.xpack.watcher.plugin.version", "", Function.identity(), Setting.Property.IndexScope);
    public static final Setting<String> INDEX_WATCHER_TEMPLATE_VERSION_SETTING =
            new Setting<>("index.xpack.watcher.template.version", "", Function.identity(), Setting.Property.IndexScope);
    public static final Setting<Boolean> ENCRYPT_SENSITIVE_DATA_SETTING =
            Setting.boolSetting("xpack.watcher.encrypt_sensitive_data", false, Setting.Property.NodeScope);

    private static final ESLogger logger = Loggers.getLogger(XPackPlugin.class);

    static {
        MetaData.registerPrototype(WatcherMetaData.TYPE, WatcherMetaData.PROTO);
    }

    protected final Settings settings;
    protected final boolean transportClient;
    protected final boolean enabled;

    public Watcher(Settings settings) {
        this.settings = settings;
        transportClient = "transport".equals(settings.get(Client.CLIENT_TYPE_SETTING_S.getKey()));
        enabled = enabled(settings);
        validAutoCreateIndex(settings);
    }

    public Collection<Module> nodeModules() {
        List<Module> modules = new ArrayList<>();
        modules.add(new WatcherModule(enabled, transportClient));
        if (enabled && transportClient == false) {
            modules.add(new WatchModule());
            modules.add(new WatcherClientModule());
            modules.add(new TransformModule());
            modules.add(new TriggerModule(settings));
            modules.add(new ScheduleModule());
            modules.add(new ConditionModule());
            modules.add(new InputModule());
            modules.add(new WatcherActionModule());
            modules.add(new HistoryModule());
            modules.add(new ExecutionModule());
        }
        return modules;
    }

    public Collection<Class<? extends LifecycleComponent>> nodeServices() {
        if (enabled == false|| transportClient) {
            return Collections.emptyList();
        }
        return Arrays.<Class<? extends LifecycleComponent>>asList(
            WatcherLicensee.class,
            WatcherSettingsValidation.class);
    }

    public Settings additionalSettings() {
        return Settings.EMPTY;
    }


    public List<Setting<?>> getSettings() {
        List<Setting<?>> settings = new ArrayList<>();
        for (TemplateConfig templateConfig : WatcherIndexTemplateRegistry.TEMPLATE_CONFIGS) {
            settings.add(templateConfig.getSetting());
        }
        settings.add(INDEX_WATCHER_VERSION_SETTING);
        settings.add(INDEX_WATCHER_TEMPLATE_VERSION_SETTING);
        settings.add(Setting.intSetting("xpack.watcher.execution.scroll.size", 0, Setting.Property.NodeScope));
        settings.add(Setting.intSetting("xpack.watcher.watch.scroll.size", 0, Setting.Property.NodeScope));
        settings.add(Setting.boolSetting(XPackPlugin.featureEnabledSetting(Watcher.NAME), true, Setting.Property.NodeScope));
        settings.add(ENCRYPT_SENSITIVE_DATA_SETTING);

        settings.add(Setting.simpleString("xpack.watcher.internal.ops.search.default_timeout", Setting.Property.NodeScope));
        settings.add(Setting.simpleString("xpack.watcher.internal.ops.bulk.default_timeout", Setting.Property.NodeScope));
        settings.add(Setting.simpleString("xpack.watcher.internal.ops.index.default_timeout", Setting.Property.NodeScope));
        settings.add(Setting.simpleString("xpack.watcher.execution.default_throttle_period", Setting.Property.NodeScope));
        settings.add(Setting.simpleString("xpack.watcher.actions.index.default_timeout", Setting.Property.NodeScope));
        settings.add(Setting.simpleString("xpack.watcher.index.rest.direct_access", Setting.Property.NodeScope));
        settings.add(Setting.simpleString("xpack.watcher.trigger.schedule.engine", Setting.Property.NodeScope));
        settings.add(Setting.simpleString("xpack.watcher.input.search.default_timeout", Setting.Property.NodeScope));
        settings.add(Setting.simpleString("xpack.watcher.transform.search.default_timeout", Setting.Property.NodeScope));
        settings.add(Setting.simpleString("xpack.watcher.trigger.schedule.ticker.tick_interval", Setting.Property.NodeScope));
        settings.add(Setting.simpleString("xpack.watcher.execution.scroll.timeout", Setting.Property.NodeScope));
        settings.add(Setting.simpleString("xpack.watcher.start_immediately", Setting.Property.NodeScope));
        return settings;
    }

    public List<ExecutorBuilder<?>> getExecutorBuilders(final Settings settings) {
        if (XPackPlugin.featureEnabled(settings, Watcher.NAME, true)) {
            final FixedExecutorBuilder builder =
                    new FixedExecutorBuilder(
                            settings,
                            InternalWatchExecutor.THREAD_POOL_NAME,
                            5 * EsExecutors.boundedNumberOfProcessors(settings),
                            1000,
                            "xpack.watcher.thread_pool");
            return Collections.singletonList(builder);
        }
        return Collections.emptyList();
    }

    public void onModule(NetworkModule module) {
        if (enabled && transportClient == false) {
            module.registerRestHandler(RestPutWatchAction.class);
            module.registerRestHandler(RestDeleteWatchAction.class);
            module.registerRestHandler(RestWatcherStatsAction.class);
            module.registerRestHandler(RestGetWatchAction.class);
            module.registerRestHandler(RestWatchServiceAction.class);
            module.registerRestHandler(RestAckWatchAction.class);
            module.registerRestHandler(RestActivateWatchAction.class);
            module.registerRestHandler(RestExecuteWatchAction.class);
            module.registerRestHandler(RestHijackOperationAction.class);
        }
    }

    public void onModule(ActionModule module) {
        if (enabled) {
            module.registerAction(PutWatchAction.INSTANCE, TransportPutWatchAction.class);
            module.registerAction(DeleteWatchAction.INSTANCE, TransportDeleteWatchAction.class);
            module.registerAction(GetWatchAction.INSTANCE, TransportGetWatchAction.class);
            module.registerAction(WatcherStatsAction.INSTANCE, TransportWatcherStatsAction.class);
            module.registerAction(AckWatchAction.INSTANCE, TransportAckWatchAction.class);
            module.registerAction(ActivateWatchAction.INSTANCE, TransportActivateWatchAction.class);
            module.registerAction(WatcherServiceAction.INSTANCE, TransportWatcherServiceAction.class);
            module.registerAction(ExecuteWatchAction.INSTANCE, TransportExecuteWatchAction.class);
        }
    }

    public void onModule(LazyInitializationModule module) {
        if (enabled) {
            module.registerLazyInitializable(WatcherClientProxy.class);
            module.registerLazyInitializable(ChainTransformFactory.class);
            module.registerLazyInitializable(ChainInputFactory.class);
        }
    }

    public static boolean enabled(Settings settings) {
        return XPackPlugin.featureEnabled(settings, NAME, true);
    }

    static void validAutoCreateIndex(Settings settings) {
        String value = settings.get("action.auto_create_index");
        if (value == null) {
            return;
        }

        String errorMessage = LoggerMessageFormat.format("the [action.auto_create_index] setting value [{}] is too" +
                " restrictive. disable [action.auto_create_index] or set it to " +
                "[.watches,.triggered_watches,.watcher-history*]", (Object) value);
        if (Booleans.isExplicitFalse(value)) {
            throw new IllegalArgumentException(errorMessage);
        }

        if (Booleans.isExplicitTrue(value)) {
            return;
        }

        String[] matches = Strings.commaDelimitedListToStringArray(value);
        List<String> indices = new ArrayList<>();
        indices.add(".watches");
        indices.add(".triggered_watches");
        DateTime now = new DateTime(DateTimeZone.UTC);
        indices.add(HistoryStore.getHistoryIndexNameForTime(now));
        indices.add(HistoryStore.getHistoryIndexNameForTime(now.plusDays(1)));
        indices.add(HistoryStore.getHistoryIndexNameForTime(now.plusMonths(1)));
        indices.add(HistoryStore.getHistoryIndexNameForTime(now.plusMonths(2)));
        indices.add(HistoryStore.getHistoryIndexNameForTime(now.plusMonths(3)));
        indices.add(HistoryStore.getHistoryIndexNameForTime(now.plusMonths(4)));
        indices.add(HistoryStore.getHistoryIndexNameForTime(now.plusMonths(5)));
        indices.add(HistoryStore.getHistoryIndexNameForTime(now.plusMonths(6)));
        for (String index : indices) {
            boolean matched = false;
            for (String match : matches) {
                char c = match.charAt(0);
                if (c == '-') {
                    if (Regex.simpleMatch(match.substring(1), index)) {
                        throw new IllegalArgumentException(errorMessage);
                    }
                } else if (c == '+') {
                    if (Regex.simpleMatch(match.substring(1), index)) {
                        matched = true;
                        break;
                    }
                } else {
                    if (Regex.simpleMatch(match, index)) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                throw new IllegalArgumentException(errorMessage);
            }
        }
        logger.warn("the [action.auto_create_index] setting is configured to be restrictive [{}]. " +
                " for the next 6 months daily history indices are allowed to be created, but please make sure" +
                " that any future history indices after 6 months with the pattern " +
                "[.watcher-history-YYYY.MM.dd] are allowed to be created", value);
    }

    
}
