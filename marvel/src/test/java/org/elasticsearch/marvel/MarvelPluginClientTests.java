/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel;

import org.elasticsearch.client.Client;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.junit.Test;

import java.util.Collection;

import static org.hamcrest.Matchers.is;

public class MarvelPluginClientTests extends ElasticsearchTestCase {

    @Test
    public void testModulesWithClientSettings() {
        Settings settings = Settings.builder()
                .put(Client.CLIENT_TYPE_SETTING, TransportClient.CLIENT_TYPE)
                .build();

        MarvelPlugin plugin = new MarvelPlugin(settings);
        assertThat(plugin.isEnabled(), is(false));
        Collection<Class<? extends Module>> modules = plugin.modules();
        assertThat(modules.size(), is(0));
    }

    @Test
    public void testModulesWithNodeSettings() {
        // these settings mimic what ES does when running as a node...
        Settings settings = Settings.builder()
                .put(Client.CLIENT_TYPE_SETTING, "node")
                .build();
        MarvelPlugin plugin = new MarvelPlugin(settings);
        assertThat(plugin.isEnabled(), is(true));
        Collection<Class<? extends Module>> modules = plugin.modules();
        assertThat(modules.size(), is(1));
    }

}
