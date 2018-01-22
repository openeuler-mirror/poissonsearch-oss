/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.transport.netty4;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.indices.breaker.CircuitBreakerService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.security.transport.filter.IPFilter;
import org.elasticsearch.xpack.ssl.SSLConfiguration;
import org.elasticsearch.xpack.ssl.SSLService;

public class SecurityNetty4ServerTransport extends SecurityNetty4Transport {

    @Nullable private final IPFilter authenticator;

    public SecurityNetty4ServerTransport(
            final Settings settings,
            final ThreadPool threadPool,
            final NetworkService networkService,
            final BigArrays bigArrays,
            final NamedWriteableRegistry namedWriteableRegistry,
            final CircuitBreakerService circuitBreakerService,
            @Nullable final IPFilter authenticator,
            final SSLService sslService) {
        super(settings, threadPool, networkService, bigArrays, namedWriteableRegistry, circuitBreakerService, sslService);
        this.authenticator = authenticator;
    }

    @Override
    protected void doStart() {
        super.doStart();
        if (authenticator != null) {
            authenticator.setBoundTransportAddress(boundAddress(), profileBoundAddresses());
        }
    }

    @Override
    protected ChannelHandler getNoSslChannelInitializer(final String name) {
        return new IPFilterServerChannelInitializer(name);
    }

    @Override
    protected ServerChannelInitializer getSslChannelInitializer(final String name, final SSLConfiguration configuration) {
        return new SecurityServerChannelInitializer(name, configuration);
    }

    public class IPFilterServerChannelInitializer extends ServerChannelInitializer {

        IPFilterServerChannelInitializer(final String name) {
            super(name);
        }

        @Override
        protected void initChannel(final Channel ch) throws Exception {
            super.initChannel(ch);
            maybeAddIPFilter(ch, name);
        }
    }

    public class SecurityServerChannelInitializer extends SslChannelInitializer {

        SecurityServerChannelInitializer(final String name, final SSLConfiguration configuration) {
            super(name, configuration);
        }

        @Override
        protected void initChannel(final Channel ch) throws Exception {
            super.initChannel(ch);
            maybeAddIPFilter(ch, name);
        }

    }

    private void maybeAddIPFilter(final Channel ch, final String name) {
        if (authenticator != null) {
            ch.pipeline().addFirst("ipfilter", new IpFilterRemoteAddressFilter(authenticator, name));
        }
    }

}
