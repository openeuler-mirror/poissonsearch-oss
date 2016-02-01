/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.marvel;

import org.apache.lucene.util.IOUtils;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.node.MockNode;
import org.elasticsearch.node.Node;
import org.elasticsearch.xpack.XPackPlugin;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

/**
 * Main class to easily run Marvel from a IDE.
 * <p>
 * In order to run this class set configure the following:
 * 1) Set `-Des.path.home=` to a directory containing an ES config directory
 *
 * It accepts collectors names as program arguments.
 */
public class MarvelF {

    public static void main(String[] args) throws Throwable {
        Settings.Builder settings = Settings.builder();
        settings.put("script.inline", "true");
        settings.put("security.manager.enabled", "false");
        settings.put("plugins.load_classpath_plugins", "false");
        settings.put("cluster.name", MarvelF.class.getSimpleName());
        settings.put("marvel.agent.interval", "5s");
        if (!CollectionUtils.isEmpty(args)) {
            settings.putArray("marvel.agent.collectors", args);
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final Node node = new MockNode(settings.build(), Version.CURRENT, Arrays.asList(XPackPlugin.class, XPackPlugin.class, XPackPlugin.class));
        Runtime.getRuntime().addShutdownHook(new Thread() {

            @Override
            public void run() {
                try {
                    IOUtils.close(node);
                } catch (IOException e) {
                    throw new ElasticsearchException(e);
                } finally {
                    latch.countDown();
                }
            }
        });
        node.start();
        latch.await();
    }

}
