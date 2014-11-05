/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.license.plugin.consumer;

import org.elasticsearch.common.component.LifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;

/**
 * Registers licenses upon the start of the service lifecycle
 * see {@link org.elasticsearch.license.plugin.consumer.EagerLicenseRegistrationPluginService}
 * <p/>
 * License registration might happen before clusterService start()
 */
public class EagerLicenseRegistrationConsumerPlugin extends TestConsumerPluginBase {

    public final static String NAME = "test_consumer_plugin_1";

    @Inject
    public EagerLicenseRegistrationConsumerPlugin(Settings settings) {
        super(settings);
    }

    @Override
    protected Class<? extends LifecycleComponent> service() {
        return EagerLicenseRegistrationPluginService.class;
    }

    @Override
    protected String pluginName() {
        return NAME;
    }
}
