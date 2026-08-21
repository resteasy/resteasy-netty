/*
 * Copyright The RESTEasy Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.resteasy.test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.core.ResteasyDeploymentImpl;
import org.jboss.resteasy.plugins.interceptors.GZIPEncodingInterceptor;
import org.jboss.resteasy.plugins.server.netty.NettyJaxrsServer;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

class JacksonResponseLifecycleTest {

    private static final String VALUE = "x".repeat(5_000);
    private static final String COMPLETE_JSON = "{\"value\":\"" + VALUE + "\"}";

    private final OutboundSequenceRecorder recorder = new OutboundSequenceRecorder();
    private NettyJaxrsServer server;
    private HttpClient client;

    private void startServer(ChannelHandler bodyWriteHandler) {
        ResteasyDeployment deployment = new ResteasyDeploymentImpl();
        deployment.setProviders(List.of(new GZIPEncodingInterceptor()));

        server = new NettyJaxrsServer();
        server.setDeployment(deployment);
        server.setHostname("127.0.0.1");
        server.setPort(0);
        server.setIoWorkerCount(1);
        server.setExecutorThreadCount(1);
        server.setHttpChannelHandlers(List.of(recorder, bodyWriteHandler));
        server.start();
        deployment.getRegistry().addPerRequestResource(JsonResource.class);

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void waitsForDelayedJacksonContentBeforeTerminatingResponse() throws Exception {
        DelayedContentHandler delayedContent = new DelayedContentHandler();
        startServer(delayedContent);

        HttpResponse<byte[]> response = client.send(request(), HttpResponse.BodyHandlers.ofByteArray());
        Assertions.assertTrue(delayedContent.awaitWrite(),
                () -> "Timed out waiting for delayed write; outbound sequence: " + recorder.messages);
        Assertions.assertNull(delayedContent.failure.get(),
                () -> "A Jackson body write ran after response termination; outbound sequence: " + recorder.messages);
        Assertions.assertEquals(COMPLETE_JSON, gunzip(response.body()));
        Assertions.assertEquals("HttpResponse", recorder.messages.get(0));
        Assertions.assertTrue(recorder.messages.contains("HttpContent"));
        Assertions.assertEquals("LastHttpContent", recorder.messages.get(recorder.messages.size() - 1));
    }

    @Test
    void closesConnectionWithoutTerminalContentWhenJacksonWriteFails() throws Exception {
        FailedContentHandler failedContent = new FailedContentHandler();
        startServer(failedContent);

        Assertions.assertThrows(IOException.class,
                () -> client.send(request(), HttpResponse.BodyHandlers.ofByteArray()));
        Assertions.assertTrue(failedContent.awaitWrite(), "Timed out waiting for failed body write");
        Assertions.assertSame(failedContent.expectedFailure, failedContent.failure.get());
        Assertions.assertEquals("HttpResponse", recorder.messages.get(0));
        Assertions.assertFalse(recorder.messages.contains("LastHttpContent"),
                () -> "A failed response was terminated successfully: " + recorder.messages);
    }

    private HttpRequest request() {
        return HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.getPort() + "/response-lifecycle"))
                .timeout(Duration.ofSeconds(5))
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT_ENCODING, "gzip")
                .GET()
                .build();
    }

    private static String gunzip(byte[] body) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(body));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Path("response-lifecycle")
    public static class JsonResource {

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public Response get() {
            return Response.ok(new JsonEntity(VALUE))
                    .header(HttpHeaders.CONTENT_ENCODING, "gzip")
                    .build();
        }
    }

    public static class JsonEntity {

        private final String value;

        JsonEntity(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private abstract static class ContentWriteHandler extends ChannelOutboundHandlerAdapter {

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);

        final boolean awaitWrite() throws InterruptedException {
            return completed.await(5, TimeUnit.SECONDS);
        }
    }

    @ChannelHandler.Sharable
    private static final class DelayedContentHandler extends ContentWriteHandler {

        private static final long WRITE_DELAY_MILLIS = 100;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof HttpContent && !(msg instanceof LastHttpContent)) {
                // Preserve the original promise and its pending state while moving actual pipeline forwarding past
                // RESTEasy dispatch. The GZIP blocking bridge returns without waiting for this legitimate promise.
                promise.addListener(future -> {
                    if (!future.isSuccess()) {
                        failure.compareAndSet(null, future.cause());
                    }
                    completed.countDown();
                });
                ctx.executor().schedule(
                        () -> ctx.writeAndFlush(msg, promise),
                        WRITE_DELAY_MILLIS,
                        TimeUnit.MILLISECONDS);
                return;
            }
            ctx.write(msg, promise);
        }
    }

    @ChannelHandler.Sharable
    private static final class FailedContentHandler extends ContentWriteHandler {

        private final IOException expectedFailure = new IOException("synthetic response body write failure");

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof HttpContent && !(msg instanceof LastHttpContent)) {
                failure.set(expectedFailure);
                ReferenceCountUtil.release(msg);
                promise.setFailure(expectedFailure);
                completed.countDown();
                return;
            }
            ctx.write(msg, promise);
        }
    }

    @ChannelHandler.Sharable
    private static final class OutboundSequenceRecorder extends ChannelOutboundHandlerAdapter {

        private final List<String> messages = new CopyOnWriteArrayList<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof io.netty.handler.codec.http.HttpResponse) {
                messages.add("HttpResponse");
            }
            if (msg instanceof LastHttpContent) {
                messages.add("LastHttpContent");
            } else if (msg instanceof HttpContent) {
                messages.add("HttpContent");
            }
            ctx.write(msg, promise);
        }
    }
}
