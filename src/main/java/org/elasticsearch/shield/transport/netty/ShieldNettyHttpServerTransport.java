/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport.netty;

import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.netty.channel.ChannelPipeline;
import org.elasticsearch.common.netty.channel.ChannelPipelineFactory;
import org.elasticsearch.common.netty.handler.ssl.SslHandler;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.http.netty.NettyHttpServerTransport;
import org.elasticsearch.shield.ssl.ServerSSLService;
import org.elasticsearch.shield.transport.filter.IPFilter;

import javax.net.ssl.SSLEngine;

/**
 *
 */
public class ShieldNettyHttpServerTransport extends NettyHttpServerTransport {

    private final IPFilter ipFilter;
    private final ServerSSLService sslService;
    private final boolean ssl;

    @Inject
    public ShieldNettyHttpServerTransport(Settings settings, NetworkService networkService, BigArrays bigArrays,
                                          IPFilter ipFilter, ServerSSLService sslService) {
        super(settings, networkService, bigArrays);
        this.ipFilter = ipFilter;
        this.ssl = settings.getAsBoolean("shield.http.ssl", false);
        this.sslService =  sslService;
    }

    @Override
    public ChannelPipelineFactory configureServerChannelPipelineFactory() {
        return new HttpSslChannelPipelineFactory(this);
    }

    private class HttpSslChannelPipelineFactory extends HttpChannelPipelineFactory {

        public HttpSslChannelPipelineFactory(NettyHttpServerTransport transport) {
            super(transport);
        }

        @Override
        public ChannelPipeline getPipeline() throws Exception {
            ChannelPipeline pipeline = super.getPipeline();
            if (ssl) {
                SSLEngine engine = sslService.createSSLEngine();
                engine.setUseClientMode(false);
                engine.setNeedClientAuth(settings.getAsBoolean("shield.http.ssl.client.auth", false));

                pipeline.addFirst("ssl", new SslHandler(engine));
            }
            pipeline.addFirst("ipfilter", new IPFilterNettyUpstreamHandler(ipFilter, IPFilter.HTTP_PROFILE_NAME));
            return pipeline;
        }
    }
}
