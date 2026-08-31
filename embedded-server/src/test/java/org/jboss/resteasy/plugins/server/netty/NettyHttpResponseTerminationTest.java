/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.resteasy.plugins.server.netty;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.LastHttpContent;

class NettyHttpResponseTerminationTest {

    @Test
    void reusesErrorResponseWhenFinishRunsAfterSendError() throws Exception {
        TestChannel testChannel = new TestChannel();
        try {
            NettyHttpResponse response = testChannel.newResponse(null);

            response.sendError(500, "failure");
            response.finish();
            testChannel.channel.runPendingTasks();

            Assertions.assertEquals(List.of("FullHttpResponse:500"), testChannel.recorder.messages);
        } finally {
            testChannel.close();
        }
    }

    @Test
    void rejectsSendErrorAfterEmptyResponseTermination() throws Exception {
        TestChannel testChannel = new TestChannel();
        try {
            NettyHttpResponse response = testChannel.newResponse(null);

            response.finish();

            Assertions.assertThrows(IllegalStateException.class, () -> response.sendError(500, "failure"));
            Assertions.assertEquals(List.of("FullHttpResponse:200"), testChannel.recorder.messages);
        } finally {
            testChannel.close();
        }
    }

    @Test
    void rejectsSendErrorAfterHeadResponseTermination() throws Exception {
        TestChannel testChannel = new TestChannel();
        try {
            NettyHttpResponse response = testChannel.newResponse(HttpMethod.HEAD);

            response.finish();

            Assertions.assertThrows(IllegalStateException.class, () -> response.sendError(500, "failure"));
            Assertions.assertEquals(List.of("FullHttpResponse:200"), testChannel.recorder.messages);
        } finally {
            testChannel.close();
        }
    }

    @Test
    void repeatedFinishWritesOneTerminalResponse() throws Exception {
        TestChannel testChannel = new TestChannel();
        try {
            NettyHttpResponse response = testChannel.newResponse(null);

            response.finish();
            response.finish();
            testChannel.channel.runPendingTasks();

            Assertions.assertEquals(List.of("FullHttpResponse:200"), testChannel.recorder.messages);
        } finally {
            testChannel.close();
        }
    }

    @Test
    void serializesEmptyTerminationAndSendError() throws Exception {
        TestChannel testChannel = new TestChannel();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            BlockingEmptyResponse response = new BlockingEmptyResponse(
                    testChannel.capture.context, ResteasyProviderFactory.getInstance());

            CompletableFuture<Void> finish = CompletableFuture.runAsync(() -> finish(response), executor);
            Assertions.assertTrue(response.awaitTerminalSelection(), "Timed out waiting for empty response selection");

            CompletableFuture<Throwable> sendError = CompletableFuture.supplyAsync(() -> {
                try {
                    response.sendError(500, "failure");
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            }, executor);
            Assertions.assertTrue(response.awaitSendErrorAttempt(), "Timed out starting sendError");
            Assertions.assertFalse(sendError.isDone(), "sendError must wait for the terminal response transition");

            response.allowTerminalSelection();
            finish.get(5, TimeUnit.SECONDS);
            Assertions.assertInstanceOf(IllegalStateException.class, sendError.get(5, TimeUnit.SECONDS));
            Assertions.assertEquals(List.of("FullHttpResponse:200"), testChannel.recorder.messages);
        } finally {
            executor.shutdownNow();
            testChannel.close();
        }
    }

    private static void finish(NettyHttpResponse response) {
        try {
            response.finish();
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private static final class BlockingEmptyResponse extends NettyHttpResponse {

        private final CountDownLatch terminalSelectionStarted = new CountDownLatch(1);
        private final CountDownLatch terminalSelectionAllowed = new CountDownLatch(1);
        private final CountDownLatch sendErrorAttempted = new CountDownLatch(1);

        private BlockingEmptyResponse(ChannelHandlerContext ctx, ResteasyProviderFactory providerFactory) {
            super(ctx, true, providerFactory);
        }

        @Override
        public DefaultHttpResponse getEmptyHttpResponse() {
            terminalSelectionStarted.countDown();
            try {
                if (!terminalSelectionAllowed.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to continue empty response selection");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to continue empty response selection", e);
            }
            return super.getEmptyHttpResponse();
        }

        @Override
        public void sendError(int status, String message) throws IOException {
            sendErrorAttempted.countDown();
            super.sendError(status, message);
        }

        private boolean awaitTerminalSelection() throws InterruptedException {
            return terminalSelectionStarted.await(5, TimeUnit.SECONDS);
        }

        private boolean awaitSendErrorAttempt() throws InterruptedException {
            return sendErrorAttempted.await(5, TimeUnit.SECONDS);
        }

        private void allowTerminalSelection() {
            terminalSelectionAllowed.countDown();
        }
    }

    private static final class TestChannel {

        private final OutboundRecorder recorder = new OutboundRecorder();
        private final ContextCapture capture = new ContextCapture();
        private final EmbeddedChannel channel = new EmbeddedChannel(recorder, capture);

        private NettyHttpResponse newResponse(HttpMethod method) {
            return new NettyHttpResponse(capture.context, true, ResteasyProviderFactory.getInstance(), method);
        }

        private void close() {
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

    private static final class OutboundRecorder extends ChannelOutboundHandlerAdapter {

        private final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof DefaultFullHttpResponse) {
                messages.add("FullHttpResponse:"
                        + ((DefaultFullHttpResponse) msg).status().code());
            } else if (msg instanceof LastHttpContent) {
                messages.add("LastHttpContent");
            } else if (msg instanceof io.netty.handler.codec.http.HttpResponse) {
                messages.add("HttpResponse");
            }
            ctx.write(msg, promise);
        }
    }
}
