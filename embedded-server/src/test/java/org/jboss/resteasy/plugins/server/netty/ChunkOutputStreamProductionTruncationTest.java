/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.resteasy.plugins.server.netty;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.LastHttpContent;

class ChunkOutputStreamProductionTruncationTest {

    private static final int CHUNK_SIZE = 1_000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void keepsTerminationBehindPlainJacksonTailWithMidStringPrefix() throws Exception {
        ObjectNode json = MAPPER.createObjectNode().put("value", "x".repeat(1_500));

        assertTerminationWaitsForTail(json, 512, (byte) 'x');
    }

    @Test
    void keepsTerminationBehindPlainJacksonTailWithObjectEntryPrefix() throws Exception {
        ObjectNode json = MAPPER.createObjectNode()
                .put("padding", "x".repeat(986))
                .put("value", "ok");

        assertTerminationWaitsForTail(json, 13, (byte) ',');
    }

    @Test
    void flushesReplacementEntityStreamBeforeWaitingForRootWrites() throws Exception {
        ObjectNode json = MAPPER.createObjectNode().put("value", "x".repeat(1_500));

        assertTerminationWaitsForTail(json, 512, (byte) 'x', true);
    }

    private static void assertTerminationWaitsForTail(ObjectNode json, int tailSize, byte finalPrefixByte)
            throws Exception {
        assertTerminationWaitsForTail(json, tailSize, finalPrefixByte, false);
    }

    private static void assertTerminationWaitsForTail(ObjectNode json, int tailSize, byte finalPrefixByte,
            boolean replaceEntityStream) throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(json);
        Assertions.assertEquals(CHUNK_SIZE + tailSize, body.length);
        Assertions.assertEquals(finalPrefixByte, body[CHUNK_SIZE - 1]);

        OutboundSequenceRecorder recorder = new OutboundSequenceRecorder();
        DelayedTailHandler delayedTail = new DelayedTailHandler();
        ContextCapture capture = new ContextCapture();
        EmbeddedChannel channel = new EmbeddedChannel(new HttpResponseEncoder(), recorder, delayedTail, capture);

        try {
            NettyHttpResponse response = new NettyHttpResponse(
                    capture.context, true, ResteasyProviderFactory.getInstance());
            OutputStream output = response.getOutputStream();
            if (replaceEntityStream) {
                output = new BufferedOutputStream(output, body.length + 1);
                response.setOutputStream(output);
            }
            output.write(body);
            response.finish();
            channel.runPendingTasks();

            List<String> messagesWhileTailPending = List.copyOf(recorder.messages);
            List<Integer> bodySizesWhileTailPending = List.copyOf(recorder.bodySizes);

            delayedTail.release();
            channel.runPendingTasks();

            Assertions.assertAll(
                    () -> Assertions.assertEquals(List.of("HttpResponse", "HttpContent"), messagesWhileTailPending,
                            "The response must remain unterminated while the Jackson tail is pending"),
                    () -> Assertions.assertEquals(List.of(CHUNK_SIZE), bodySizesWhileTailPending),
                    () -> Assertions.assertEquals(
                            List.of("HttpResponse", "HttpContent", "HttpContent", "LastHttpContent"), recorder.messages),
                    () -> Assertions.assertEquals(List.of(CHUNK_SIZE, tailSize), recorder.bodySizes),
                    () -> Assertions.assertNull(delayedTail.failure.get(),
                            "A body chunk reached the HTTP encoder after response termination"));
        } finally {
            delayedTail.release();
            channel.runPendingTasks();
            channel.finishAndReleaseAll();
        }
    }

    private static final class ContextCapture extends ChannelInboundHandlerAdapter {

        private ChannelHandlerContext context;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            context = ctx;
        }
    }

    private static final class DelayedTailHandler extends ChannelOutboundHandlerAdapter {

        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private ChannelHandlerContext context;
        private HttpContent message;
        private ChannelPromise promise;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise writePromise) throws Exception {
            if (msg instanceof HttpContent && !(msg instanceof LastHttpContent)
                    && ((HttpContent) msg).content().readableBytes() < CHUNK_SIZE) {
                context = ctx;
                message = (HttpContent) msg;
                promise = writePromise;
                writePromise.addListener(future -> {
                    if (!future.isSuccess()) {
                        failure.compareAndSet(null, future.cause());
                    }
                });
                return;
            }
            ctx.write(msg, writePromise);
        }

        private void release() {
            if (message == null) {
                return;
            }
            HttpContent delayedMessage = message;
            ChannelPromise delayedPromise = promise;
            ChannelHandlerContext delayedContext = context;
            message = null;
            promise = null;
            context = null;
            delayedContext.writeAndFlush(delayedMessage, delayedPromise);
        }
    }

    private static final class OutboundSequenceRecorder extends ChannelOutboundHandlerAdapter {

        private final List<String> messages = new ArrayList<>();
        private final List<Integer> bodySizes = new ArrayList<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof io.netty.handler.codec.http.HttpResponse) {
                messages.add("HttpResponse");
            }
            if (msg instanceof LastHttpContent) {
                messages.add("LastHttpContent");
            } else if (msg instanceof HttpContent) {
                messages.add("HttpContent");
                bodySizes.add(((HttpContent) msg).content().readableBytes());
            }
            ctx.write(msg, promise);
        }
    }
}
