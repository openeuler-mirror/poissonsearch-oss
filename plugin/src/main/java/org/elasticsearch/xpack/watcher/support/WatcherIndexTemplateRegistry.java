/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.watcher.support;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest;
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateResponse;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.security.InternalClient;
import org.elasticsearch.xpack.template.TemplateUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class WatcherIndexTemplateRegistry extends AbstractComponent implements ClusterStateListener {

    public static final String INDEX_TEMPLATE_VERSION = "6";

    public static final String HISTORY_TEMPLATE_NAME = ".watch-history-" + INDEX_TEMPLATE_VERSION;
    public static final String TRIGGERED_TEMPLATE_NAME = ".triggered_watches";
    public static final String WATCHES_TEMPLATE_NAME = ".watches";

    public static final TemplateConfig[] TEMPLATE_CONFIGS = new TemplateConfig[]{
            new TemplateConfig(TRIGGERED_TEMPLATE_NAME, "triggered-watches"),
            new TemplateConfig(HISTORY_TEMPLATE_NAME, "watch-history"),
            new TemplateConfig(WATCHES_TEMPLATE_NAME, "watches")
    };

    private final InternalClient client;
    private final ThreadPool threadPool;
    private final TemplateConfig[] indexTemplates;
    private final ConcurrentMap<String, AtomicBoolean> templateCreationsInProgress = new ConcurrentHashMap<>();

    public WatcherIndexTemplateRegistry(Settings settings, ClusterService clusterService, ThreadPool threadPool, InternalClient client) {
        super(settings);
        this.client = client;
        this.threadPool = threadPool;
        this.indexTemplates = TEMPLATE_CONFIGS;
        clusterService.addListener(this);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        ClusterState state = event.state();
        if (state.blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            // wait until the gateway has recovered from disk, otherwise we think may not have the index templates,
            // while they actually do exist
            return;
        }

        if (event.localNodeMaster() == false) {
            // Only the node that runs or will run Watcher should update the templates. Otherwise unnecessary put template
            // calls would happen
            return;
        }

        addTemplatesIfMissing(state);
    }

    private void addTemplatesIfMissing(ClusterState state) {
        for (TemplateConfig template : indexTemplates) {
            final String templateName = template.getTemplateName();
            final AtomicBoolean creationCheck = templateCreationsInProgress.computeIfAbsent(templateName, key -> new AtomicBoolean(false));
            if (creationCheck.compareAndSet(false, true)) {
                if (!state.metaData().getTemplates().containsKey(templateName)) {

                    logger.debug("adding index template [{}], because it doesn't exist", templateName);
                    putTemplate(template, creationCheck);
                } else {
                    creationCheck.set(false);
                    logger.trace("not adding index template [{}], because it already exists", templateName);
                }
            }
        }
    }

    private void putTemplate(final TemplateConfig config, final AtomicBoolean creationCheck) {
        final Executor executor = threadPool.generic();
        executor.execute(() -> {
            final String templateName = config.getTemplateName();
            final byte[] template = TemplateUtils.loadTemplate("/" + config.getFileName() + ".json", INDEX_TEMPLATE_VERSION,
                    Pattern.quote("${xpack.watcher.template.version}")).getBytes(StandardCharsets.UTF_8);

            PutIndexTemplateRequest request = new PutIndexTemplateRequest(templateName).source(template, XContentType.JSON);
            request.masterNodeTimeout(TimeValue.timeValueMinutes(1));
            client.admin().indices().putTemplate(request, new ActionListener<PutIndexTemplateResponse>() {
                @Override
                public void onResponse(PutIndexTemplateResponse response) {
                    creationCheck.set(false);
                    if (response.isAcknowledged() == false) {
                        logger.error("Error adding watcher template [{}], request was not acknowledged", templateName);
                    }
                }

                @Override
                public void onFailure(Exception e) {
                    creationCheck.set(false);
                    logger.error(new ParameterizedMessage("Error adding watcher template [{}]", templateName), e);
                }
            });
        });
    }

    public static class TemplateConfig {

        private final String templateName;
        private String fileName;

        public TemplateConfig(String templateName, String fileName) {
            this.templateName = templateName;
            this.fileName = fileName;
        }

        public String getFileName() {
            return fileName;
        }

        public String getTemplateName() {
            return templateName;
        }
    }
}
