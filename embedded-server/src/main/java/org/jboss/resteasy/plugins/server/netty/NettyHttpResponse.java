/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.resteasy.plugins.server.netty;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.io.OutputStream;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;

import org.jboss.resteasy.plugins.server.netty.i18n.Messages;
import org.jboss.resteasy.specimpl.MultivaluedMapImpl;
import org.jboss.resteasy.spi.HttpResponse;
import org.jboss.resteasy.spi.ResteasyProviderFactory;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;

/**
 * Body write submission and completion are ordered by the root {@link ChunkOutputStream}'s write lock. Terminal writes
 * can be requested by RESTEasy dispatch or by an asynchronous Netty promise callback. {@link #sendError(int, String)}
 * and {@link #writeResponseTermination()} therefore synchronize on this response, making terminal message selection,
 * write submission and future publication one transition.
 *
 * @author <a href="mailto:bill@burkecentral.com">Bill Burke</a>
 * @version $Revision: 1 $
 */
public class NettyHttpResponse implements HttpResponse {
    private static final int EMPTY_CONTENT_LENGTH = 0;
    private int status = 200;
    // RESTEasy writer interceptors can replace this stream with a wrapper around the root stream.
    private OutputStream entityOutputStream;
    // The root stream owns the Netty body-write promises and therefore remains stable when the entity stream is replaced.
    private final ChunkOutputStream rootChunkOutputStream;
    private final MultivaluedMap<String, Object> outputHeaders;
    private final ChannelHandlerContext ctx;
    private boolean committed;
    private final boolean keepAlive;
    private final ResteasyProviderFactory providerFactory;
    private final HttpMethod method;
    private ChannelFuture terminationFuture;

    public NettyHttpResponse(final ChannelHandlerContext ctx, final boolean keepAlive,
            final ResteasyProviderFactory providerFactory) {
        this(ctx, keepAlive, providerFactory, null);
    }

    public NettyHttpResponse(final ChannelHandlerContext ctx, final boolean keepAlive,
            final ResteasyProviderFactory providerFactory, final HttpMethod method) {
        outputHeaders = new MultivaluedMapImpl<String, Object>();
        this.method = method;
        rootChunkOutputStream = (method == null || !method.equals(HttpMethod.HEAD))
                ? new ChunkOutputStream(this, ctx, 1000)
                : null; //[RESTEASY-1627]
        entityOutputStream = rootChunkOutputStream;
        this.ctx = ctx;
        this.keepAlive = keepAlive;
        this.providerFactory = providerFactory;
    }

    @Override
    public void setOutputStream(OutputStream entityOutputStream) {
        this.entityOutputStream = entityOutputStream;
    }

    @Override
    public int getStatus() {
        return status;
    }

    @Override
    public void setStatus(int status) {
        this.status = status;
    }

    @Override
    public MultivaluedMap<String, Object> getOutputHeaders() {
        return outputHeaders;
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return entityOutputStream;
    }

    @Override
    public void addNewCookie(NewCookie cookie) {
        outputHeaders.add(jakarta.ws.rs.core.HttpHeaders.SET_COOKIE, cookie);
    }

    @Override
    public void sendError(int status) throws IOException {
        sendError(status, null);
    }

    @Override
    public synchronized void sendError(int status, String message) throws IOException {
        if (committed) {
            throw new IllegalStateException();
        }

        setStatus(status);
        io.netty.handler.codec.http.HttpResponse response = null;
        if (message != null) {
            byte[] messageBytes = message.getBytes();
            ByteBuf byteBuf = ctx.alloc().buffer();
            byteBuf.writeBytes(messageBytes);
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, new HttpResponseStatus(status, message), byteBuf);
            response.headers().add(HttpHeaderNames.CONTENT_LENGTH, messageBytes.length);
        } else {
            response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(status));
            response.headers().add(HttpHeaderNames.CONTENT_LENGTH, 0);
        }
        // Add keep alive or connection close header
        transformResponseHeaders(response);
        committed = true;
        terminationFuture = ctx.writeAndFlush(response);
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }

    @Override
    public void reset() {
        if (committed) {
            throw new IllegalStateException(Messages.MESSAGES.alreadyCommitted());
        }
        outputHeaders.clear();
    }

    public boolean isKeepAlive() {
        return keepAlive;
    }

    public DefaultHttpResponse getDefaultHttpResponse() {
        DefaultHttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(getStatus()));
        transformResponseHeaders(res);
        return res;
    }

    public DefaultHttpResponse getEmptyHttpResponse() {
        DefaultFullHttpResponse res = new DefaultFullHttpResponse(HTTP_1_1, HttpResponseStatus.valueOf(getStatus()));
        if (method == null || !method.equals(HttpMethod.HEAD)) //[RESTEASY-1627]
        {
            res.headers().add(HttpHeaderNames.CONTENT_LENGTH, EMPTY_CONTENT_LENGTH);
        }
        transformResponseHeaders(res);
        return res;
    }

    private void transformResponseHeaders(io.netty.handler.codec.http.HttpResponse res) {
        RestEasyHttpResponseEncoder.transformHeaders(this, res, providerFactory);
    }

    /**
     * Called by {@link ChunkOutputStream} while it holds its write lock. Body-write completion reacquires that lock before
     * it can request termination, which makes this committed state visible to the completion path.
     */
    public void prepareChunkStream() {
        committed = true;
        DefaultHttpResponse response = getDefaultHttpResponse();
        HttpUtil.setTransferEncodingChunked(response, true);
        ctx.write(response);
    }

    public void finish() throws IOException {
        ChannelFuture future;
        if (rootChunkOutputStream != null) {
            future = rootChunkOutputStream.finish(entityOutputStream);
        } else {
            future = writeResponseTermination();
        }

        if (!isKeepAlive()) {
            future.addListener(ChannelFutureListener.CLOSE);
        }

    }

    synchronized ChannelFuture writeResponseTermination() {
        if (terminationFuture != null) {
            return terminationFuture;
        }
        Object terminalMessage = isCommitted() ? LastHttpContent.EMPTY_LAST_CONTENT : getEmptyHttpResponse();
        committed = true;
        terminationFuture = ctx.writeAndFlush(terminalMessage);
        return terminationFuture;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (entityOutputStream != null)
            entityOutputStream.flush();
        ctx.flush();
    }
}
