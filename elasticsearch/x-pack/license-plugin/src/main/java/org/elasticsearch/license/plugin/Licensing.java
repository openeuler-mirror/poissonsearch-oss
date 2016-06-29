/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.license.plugin.action.delete.DeleteLicenseAction;
import org.elasticsearch.license.plugin.action.delete.TransportDeleteLicenseAction;
import org.elasticsearch.license.plugin.action.get.GetLicenseAction;
import org.elasticsearch.license.plugin.action.get.TransportGetLicenseAction;
import org.elasticsearch.license.plugin.action.put.PutLicenseAction;
import org.elasticsearch.license.plugin.action.put.TransportPutLicenseAction;
import org.elasticsearch.license.plugin.core.LicensesMetaData;
import org.elasticsearch.license.plugin.core.LicensesService;
import org.elasticsearch.license.plugin.rest.RestDeleteLicenseAction;
import org.elasticsearch.license.plugin.rest.RestGetLicenseAction;
import org.elasticsearch.license.plugin.rest.RestPutLicenseAction;
import org.elasticsearch.plugins.ActionPlugin;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static java.util.Collections.emptyList;
import static org.elasticsearch.xpack.XPackPlugin.isTribeNode;
import static org.elasticsearch.xpack.XPackPlugin.transportClientMode;


public class Licensing implements ActionPlugin {

    public static final String NAME = "license";
    private final boolean isTransportClient;
    private final boolean isTribeNode;

    static {
        MetaData.registerPrototype(LicensesMetaData.TYPE, LicensesMetaData.PROTO);
    }

    @Inject
    public Licensing(Settings settings) {
        isTransportClient = transportClientMode(settings);
        isTribeNode = isTribeNode(settings);
    }

    public void onModule(NetworkModule module) {
        if (isTransportClient == false && isTribeNode == false) {
            module.registerRestHandler(RestPutLicenseAction.class);
            module.registerRestHandler(RestGetLicenseAction.class);
            module.registerRestHandler(RestDeleteLicenseAction.class);
        }
    }

    @Override
    public List<ActionHandler<? extends ActionRequest<?>, ? extends ActionResponse>> getActions() {
        if (isTribeNode) {
            return emptyList();
        }
        return Arrays.asList(new ActionHandler<>(PutLicenseAction.INSTANCE, TransportPutLicenseAction.class),
                new ActionHandler<>(GetLicenseAction.INSTANCE, TransportGetLicenseAction.class),
                new ActionHandler<>(DeleteLicenseAction.INSTANCE, TransportDeleteLicenseAction.class));
    }

    public Collection<Class<? extends LifecycleComponent>> nodeServices() {
        if (isTransportClient == false && isTribeNode == false) {
            return Collections.<Class<? extends LifecycleComponent>>singletonList(LicensesService.class);
        }
        return Collections.emptyList();
    }

    public Collection<Module> nodeModules() {
        if (isTransportClient == false && isTribeNode == false) {
            return Collections.<Module>singletonList(new LicensingModule());
        }
        return Collections.emptyList();
    }

    public List<Setting<?>> getSettings() {
        // TODO convert this wildcard to a real setting
        return Collections.singletonList(Setting.groupSetting("license.", Setting.Property.NodeScope));
    }
}
