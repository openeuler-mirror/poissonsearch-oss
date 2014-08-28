/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.transport.netty;

import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.netty.channel.ChannelFuture;
import org.elasticsearch.common.netty.channel.ChannelFutureListener;
import org.elasticsearch.common.netty.channel.ChannelHandlerContext;
import org.elasticsearch.common.netty.channel.ChannelStateEvent;
import org.elasticsearch.common.netty.handler.ssl.SslHandler;
import org.elasticsearch.shield.transport.ssl.ElasticsearchSSLException;
import org.elasticsearch.transport.netty.MessageChannelHandler;

public class SecuredMessageChannelHandler extends MessageChannelHandler {

    public SecuredMessageChannelHandler(org.elasticsearch.transport.netty.NettyTransport transport, ESLogger logger) {
        super(transport, logger);
    }

    @Override
    public void channelConnected(final ChannelHandlerContext ctx, final ChannelStateEvent e) throws Exception {
        SslHandler sslHandler = ctx.getPipeline().get(SslHandler.class);

        // Get notified when SSL handshake is done.
        final ChannelFuture handshakeFuture = sslHandler.handshake();
        handshakeFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    logger.debug("SSL / TLS handshake completed for channel", ctx.getName());
                    ctx.sendUpstream(e);
                } else {
                    logger.error("SSL / TLS handshake failed, closing channel", ctx.getName());
                    future.getChannel().close();
                    throw new ElasticsearchSSLException("SSL / TLS handshake failed, closing the channel", future.getCause());
                }
            }
        });
    }
}
