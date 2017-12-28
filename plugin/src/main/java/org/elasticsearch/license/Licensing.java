/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.cluster.NamedDiff;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.IndexScopedSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.settings.SettingsFilter;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import static org.elasticsearch.xpack.XPackClientActionPlugin.isTribeNode;
import static org.elasticsearch.xpack.XPackPlugin.transportClientMode;

public class Licensing implements ActionPlugin {

    public static final String NAME = "license";
    protected final Settings settings;
    private final boolean isTransportClient;
    private final boolean isTribeNode;

    public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
        List<NamedWriteableRegistry.Entry> entries = new ArrayList<>();
        entries.add(new NamedWriteableRegistry.Entry(MetaData.Custom.class, LicensesMetaData.TYPE, LicensesMetaData::new));
        entries.add(new NamedWriteableRegistry.Entry(NamedDiff.class, LicensesMetaData.TYPE, LicensesMetaData::readDiffFrom));
        return entries;
    }

    public List<NamedXContentRegistry.Entry> getNamedXContent() {
        List<NamedXContentRegistry.Entry> entries = new ArrayList<>();
        // Metadata
        entries.add(new NamedXContentRegistry.Entry(MetaData.Custom.class, new ParseField(LicensesMetaData.TYPE),
                LicensesMetaData::fromXContent));
        return entries;
    }

    public Licensing(Settings settings) {
        this.settings = settings;
        isTransportClient = transportClientMode(settings);
        isTribeNode = isTribeNode(settings);
    }

    @Override
    public List<ActionHandler<? extends ActionRequest, ? extends ActionResponse>> getActions() {
        if (isTribeNode) {
            return Collections.singletonList(new ActionHandler<>(GetLicenseAction.INSTANCE, TransportGetLicenseAction.class));
        }
        return Arrays.asList(new ActionHandler<>(PutLicenseAction.INSTANCE, TransportPutLicenseAction.class),
                new ActionHandler<>(GetLicenseAction.INSTANCE, TransportGetLicenseAction.class),
                new ActionHandler<>(DeleteLicenseAction.INSTANCE, TransportDeleteLicenseAction.class),
                new ActionHandler<>(PostStartTrialAction.INSTANCE, TransportPostStartTrialAction.class),
                new ActionHandler<>(GetTrialStatusAction.INSTANCE, TransportGetTrialStatusAction.class));
    }

    @Override
    public List<RestHandler> getRestHandlers(Settings settings, RestController restController, ClusterSettings clusterSettings,
            IndexScopedSettings indexScopedSettings, SettingsFilter settingsFilter, IndexNameExpressionResolver indexNameExpressionResolver,
            Supplier<DiscoveryNodes> nodesInCluster) {
        List<RestHandler> handlers = new ArrayList<>();
        handlers.add(new RestGetLicenseAction(settings, restController));
        if (false == isTribeNode) {
            handlers.add(new RestPutLicenseAction(settings, restController));
            handlers.add(new RestDeleteLicenseAction(settings, restController));
            handlers.add(new RestGetTrialStatus(settings, restController));
            handlers.add(new RestPostStartTrialLicense(settings, restController));
        }
        return handlers;
    }

    public List<Setting<?>> getSettings() {
        // TODO convert this wildcard to a real setting
        return Collections.singletonList(Setting.groupSetting("license.", Setting.Property.NodeScope));
    }

}
