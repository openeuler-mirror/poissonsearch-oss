/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.transport.netty;

import org.elasticsearch.common.io.ThrowableObjectInputStream;
import org.elasticsearch.common.io.stream.CachedStreamInput;
import org.elasticsearch.common.io.stream.HandlesStreamInput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.Streamable;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.netty.buffer.ChannelBuffer;
import org.elasticsearch.common.netty.buffer.ChannelBuffers;
import org.elasticsearch.common.netty.channel.Channel;
import org.elasticsearch.common.netty.channel.ChannelHandlerContext;
import org.elasticsearch.common.netty.channel.ChannelStateEvent;
import org.elasticsearch.common.netty.channel.ExceptionEvent;
import org.elasticsearch.common.netty.channel.MessageEvent;
import org.elasticsearch.common.netty.channel.SimpleChannelUpstreamHandler;
import org.elasticsearch.common.netty.channel.WriteCompletionEvent;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.ActionNotFoundTransportException;
import org.elasticsearch.transport.RemoteTransportException;
import org.elasticsearch.transport.ResponseHandlerFailureTransportException;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportResponseHandler;
import org.elasticsearch.transport.TransportSerializationException;
import org.elasticsearch.transport.TransportServiceAdapter;
import org.elasticsearch.transport.support.TransportStreams;

import java.io.IOException;
import java.io.StreamCorruptedException;
import java.net.SocketAddress;

/**
 * A handler (must be the last one!) that does size based frame decoding and forwards the actual message
 * to the relevant action.
 */
public class MessageChannelHandler extends SimpleChannelUpstreamHandler {

    private final ESLogger logger;

    private final ThreadPool threadPool;

    private final TransportServiceAdapter transportServiceAdapter;

    private final NettyTransport transport;

    // from FrameDecoder
    private ChannelBuffer cumulation;

    public MessageChannelHandler(NettyTransport transport, ESLogger logger) {
        this.threadPool = transport.threadPool();
        this.transportServiceAdapter = transport.transportServiceAdapter();
        this.transport = transport;
        this.logger = logger;
    }

    @Override public void writeComplete(ChannelHandlerContext ctx, WriteCompletionEvent e) throws Exception {
        transportServiceAdapter.sent(e.getWrittenAmount());
        super.writeComplete(ctx, e);
    }

    // similar logic to FrameDecoder, we don't use FrameDecoder because we can use the data len header value
    // to guess the size of the cumulation buffer to allocate
    // Also strange, is that the FrameDecoder always allocated a cumulation, even if the input bufer is enough
    // so we don't allocate a cumulation buffer unless we really need to here (need to post this to the mailing list)
    @Override public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {

        Object m = e.getMessage();
        if (!(m instanceof ChannelBuffer)) {
            ctx.sendUpstream(e);
            return;
        }

        ChannelBuffer input = (ChannelBuffer) m;
        if (!input.readable()) {
            return;
        }

        ChannelBuffer cumulation = this.cumulation;
        if (cumulation != null && cumulation.readable()) {
            cumulation.discardReadBytes();
            cumulation.writeBytes(input);
            callDecode(ctx, e.getChannel(), cumulation, e.getRemoteAddress());
        } else {
            int actualSize = callDecode(ctx, e.getChannel(), input, e.getRemoteAddress());
            if (input.readable()) {
                if (actualSize > 0) {
                    cumulation = ChannelBuffers.dynamicBuffer(actualSize, ctx.getChannel().getConfig().getBufferFactory());
                } else {
                    cumulation = ChannelBuffers.dynamicBuffer(ctx.getChannel().getConfig().getBufferFactory());
                }
                cumulation.writeBytes(input);
                this.cumulation = cumulation;
            }
        }
    }

    @Override
    public void channelDisconnected(
            ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        cleanup(ctx, e);
    }

    @Override
    public void channelClosed(
            ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        cleanup(ctx, e);
    }

    private int callDecode(ChannelHandlerContext context, Channel channel, ChannelBuffer cumulation, SocketAddress remoteAddress) throws Exception {
        while (cumulation.readable()) {
            // Changes from Frame Decoder, to combine SizeHeader and this decoder into one...
            if (cumulation.readableBytes() < 4) {
                break; // we need more data
            }

            int dataLen = cumulation.getInt(cumulation.readerIndex());
            if (dataLen <= 0) {
                throw new StreamCorruptedException("invalid data length: " + dataLen);
            }

            int actualSize = dataLen + 4;
            if (cumulation.readableBytes() < actualSize) {
                return actualSize;
            }

            cumulation.skipBytes(4);

            process(context, channel, cumulation, dataLen);
        }

        // TODO: we can potentially create a cumulation buffer cache, pop/push style
        if (!cumulation.readable()) {
            this.cumulation = null;
        }

        return 0;
    }


    private void cleanup(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        try {
            ChannelBuffer cumulation = this.cumulation;
            if (cumulation == null) {
                return;
            } else {
                this.cumulation = null;
            }

            if (cumulation.readable()) {
                // Make sure all frames are read before notifying a closed channel.
                callDecode(ctx, ctx.getChannel(), cumulation, null);
            }

            // Call decodeLast() finally.  Please note that decodeLast() is
            // called even if there's nothing more to read from the buffer to
            // notify a user that the connection was closed explicitly.

            // Change from FrameDecoder: we don't need it...
//            Object partialFrame = decodeLast(ctx, ctx.getChannel(), cumulation);
//            if (partialFrame != null) {
//                unfoldAndFireMessageReceived(ctx, null, partialFrame);
//            }
        } finally {
            ctx.sendUpstream(e);
        }
    }

    private void process(ChannelHandlerContext ctx, Channel channel, ChannelBuffer buffer, int size) throws Exception {
        transportServiceAdapter.received(size + 4);

        int markedReaderIndex = buffer.readerIndex();
        int expectedIndexReader = markedReaderIndex + size;

        StreamInput streamIn = new ChannelBufferStreamInput(buffer, size);

        long requestId = buffer.readLong();
        byte status = buffer.readByte();
        boolean isRequest = TransportStreams.statusIsRequest(status);

        HandlesStreamInput handlesStream;
        if (TransportStreams.statusIsCompress(status)) {
            handlesStream = CachedStreamInput.cachedHandlesLzf(streamIn);
        } else {
            handlesStream = CachedStreamInput.cachedHandles(streamIn);
        }

        if (isRequest) {
            String action = handleRequest(channel, handlesStream, requestId);
            if (buffer.readerIndex() != expectedIndexReader) {
                if (buffer.readerIndex() < expectedIndexReader) {
                    logger.warn("Message not fully read (request) for [{}] and action [{}], resetting", requestId, action);
                } else {
                    logger.warn("Message read past expected size (request) for [{}] and action [{}], resetting", requestId, action);
                }
                buffer.readerIndex(expectedIndexReader);
            }
        } else {
            TransportResponseHandler handler = transportServiceAdapter.remove(requestId);
            // ignore if its null, the adapter logs it
            if (handler != null) {
                if (TransportStreams.statusIsError(status)) {
                    handlerResponseError(handlesStream, handler);
                } else {
                    handleResponse(handlesStream, handler);
                }
            } else {
                // if its null, skip those bytes
                buffer.readerIndex(markedReaderIndex + size);
            }
            if (buffer.readerIndex() != expectedIndexReader) {
                if (buffer.readerIndex() < expectedIndexReader) {
                    logger.warn("Message not fully read (response) for [{}] handler {}, error [{}], resetting", requestId, handler, TransportStreams.statusIsError(status));
                } else {
                    logger.warn("Message read past expected size (response) for [{}] handler {}, error [{}], resetting", requestId, handler, TransportStreams.statusIsError(status));
                }
                buffer.readerIndex(expectedIndexReader);
            }
        }
        handlesStream.cleanHandles();
    }

    private void handleResponse(StreamInput buffer, final TransportResponseHandler handler) {
        final Streamable streamable = handler.newInstance();
        try {
            streamable.readFrom(buffer);
        } catch (Exception e) {
            handleException(handler, new TransportSerializationException("Failed to deserialize response of type [" + streamable.getClass().getName() + "]", e));
            return;
        }
        try {
            if (handler.executor() == ThreadPool.Names.SAME) {
                //noinspection unchecked
                handler.handleResponse(streamable);
            } else {
                threadPool.executor(handler.executor()).execute(new ResponseHandler(handler, streamable));
            }
        } catch (Exception e) {
            handleException(handler, new ResponseHandlerFailureTransportException(e));
        }
    }

    private void handlerResponseError(StreamInput buffer, final TransportResponseHandler handler) {
        Throwable error;
        try {
            ThrowableObjectInputStream ois = new ThrowableObjectInputStream(buffer);
            error = (Throwable) ois.readObject();
        } catch (Exception e) {
            error = new TransportSerializationException("Failed to deserialize exception response from stream", e);
        }
        handleException(handler, error);
    }

    private void handleException(final TransportResponseHandler handler, Throwable error) {
        if (!(error instanceof RemoteTransportException)) {
            error = new RemoteTransportException(error.getMessage(), error);
        }
        final RemoteTransportException rtx = (RemoteTransportException) error;
        if (handler.executor() == ThreadPool.Names.SAME) {
            handler.handleException(rtx);
        } else {
            threadPool.executor(handler.executor()).execute(new Runnable() {
                @Override public void run() {
                    try {
                        handler.handleException(rtx);
                    } catch (Exception e) {
                        logger.error("Failed to handle exception response", e);
                    }
                }
            });
        }
    }

    private String handleRequest(Channel channel, StreamInput buffer, long requestId) throws IOException {
        final String action = buffer.readUTF();

        final NettyTransportChannel transportChannel = new NettyTransportChannel(transport, action, channel, requestId);
        try {
            final TransportRequestHandler handler = transportServiceAdapter.handler(action);
            if (handler == null) {
                throw new ActionNotFoundTransportException(action);
            }
            final Streamable streamable = handler.newInstance();
            streamable.readFrom(buffer);
            if (handler.executor() == ThreadPool.Names.SAME) {
                //noinspection unchecked
                handler.messageReceived(streamable, transportChannel);
            } else {
                threadPool.executor(handler.executor()).execute(new RequestHandler(handler, streamable, transportChannel, action));
            }
        } catch (Exception e) {
            try {
                transportChannel.sendResponse(e);
            } catch (IOException e1) {
                logger.warn("Failed to send error message back to client for action [" + action + "]", e);
                logger.warn("Actual Exception", e1);
            }
        }
        return action;
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
        transport.exceptionCaught(ctx, e);
    }

    class ResponseHandler implements Runnable {

        private final TransportResponseHandler handler;
        private final Streamable streamable;

        public ResponseHandler(TransportResponseHandler handler, Streamable streamable) {
            this.handler = handler;
            this.streamable = streamable;
        }

        @SuppressWarnings({"unchecked"}) @Override public void run() {
            try {
                handler.handleResponse(streamable);
            } catch (Exception e) {
                handleException(handler, new ResponseHandlerFailureTransportException(e));
            }
        }
    }

    class RequestHandler implements Runnable {
        private final TransportRequestHandler handler;
        private final Streamable streamable;
        private final NettyTransportChannel transportChannel;
        private final String action;

        public RequestHandler(TransportRequestHandler handler, Streamable streamable, NettyTransportChannel transportChannel, String action) {
            this.handler = handler;
            this.streamable = streamable;
            this.transportChannel = transportChannel;
            this.action = action;
        }

        @SuppressWarnings({"unchecked"}) @Override public void run() {
            try {
                handler.messageReceived(streamable, transportChannel);
            } catch (Throwable e) {
                try {
                    transportChannel.sendResponse(e);
                } catch (IOException e1) {
                    logger.warn("Failed to send error message back to client for action [" + action + "]", e1);
                    logger.warn("Actual Exception", e);
                }
            }
        }
    }
}
