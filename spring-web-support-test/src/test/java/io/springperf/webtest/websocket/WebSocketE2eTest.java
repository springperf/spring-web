package io.springperf.webtest.websocket;

import io.springperf.webtest.BaseE2ETest;
import okhttp3.*;
import okio.ByteString;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketE2eTest extends BaseE2ETest {

    private WebSocket webSocket;
    private OkHttpClient wsClient;

    @AfterEach
    void tearDown() {
        if (webSocket != null) {
            webSocket.close(1000, "test done");
        }
        if (wsClient != null) {
            wsClient.dispatcher().executorService().shutdown();
        }
    }

    // ========= 基础功能（已有用例保持兼容） =========

    @Test
    void sendText_receivesEcho() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        wsClient = new OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url("ws://localhost:9090/ws/echo").build();
        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.send("hello echo");
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                received.set(text);
                latch.countDown();
            }

            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "timeout waiting for echo");
        assertEquals("hello echo", received.get());
    }

    @Test
    void close_returnsNormalCloseCode() throws Exception {
        CountDownLatch closeLatch = new CountDownLatch(1);
        AtomicReference<Integer> closeCode = new AtomicReference<>();

        wsClient = new OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url("ws://localhost:9090/ws/echo").build();
        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.close(1000, "client closing");
            }

            @Override
            public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
                closeCode.set(code);
                closeLatch.countDown();
            }

            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
                closeLatch.countDown();
            }
        });

        assertTrue(closeLatch.await(5, TimeUnit.SECONDS), "timeout waiting for close");
        assertEquals(1000, closeCode.get());
    }

    @Test
    void multipleTextMessages_allEchoed() throws Exception {
        CountDownLatch latch = new CountDownLatch(3);
        java.util.List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();

        wsClient = new OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url("ws://localhost:9090/ws/echo").build();
        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.send("msg1");
                ws.send("msg2");
                ws.send("msg3");
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                received.add(text);
                latch.countDown();
            }

            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
                while (latch.getCount() > 0) latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "timeout waiting for messages");
        assertEquals(3, received.size());
        assertTrue(received.containsAll(java.util.Arrays.asList("msg1", "msg2", "msg3")));
    }

    // ========= 新增高价值测试用例 =========

    /**
     * 验证二进制消息的编解码和回显。
     * 覆盖 {@code NettyWebSocketSession.toFrame()} 的 BinaryWebSocketFrame 分支
     * 和 {@code WebSocketRoutingHandler.handleWebSocketFrame()} 的 BinaryWebSocketFrame 分支。
     *
     * 价值：二进制帧处理是完全独立的代码路径，Text/Binary 共享逻辑极少，无覆盖则二进制帧可能完全不可用。
     */
    @Test
    void sendBinary_receivesBinaryEcho() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ByteString> received = new AtomicReference<>();
        byte[] payload = "binary-data-你好".getBytes(StandardCharsets.UTF_8);

        wsClient = new OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url("ws://localhost:9090/ws/echo").build();
        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.send(ByteString.of(payload));
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull ByteString bytes) {
                received.set(bytes);
                latch.countDown();
            }

            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "timeout waiting for binary echo");
        assertArrayEquals(payload, received.get().toByteArray());
    }

    /**
     * 验证路径变量被正确提取并注入 session attributes。
     * 覆盖 {@code WebSocketRoutingHandler.resolveHandler()} 的 matchAndExtract 路径
     * 和 {@code afterHandshakeSuccess()} 的 attributes.putAll 路径。
     *
     * 价值：P1.1 新功能，路由层与业务层的数据传递通道，断裂会导致路径变量完全不可用。
     */
    @Test
    void pathVariable_roomIdInjected() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> received = new AtomicReference<>();

        wsClient = new OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url("ws://localhost:9090/ws/room/myroom123").build();
        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.send("hello");
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                received.set(text);
                latch.countDown();
            }

            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
                latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "timeout waiting for path echo");
        assertEquals("room=myroom123 msg=hello", received.get());
    }

    /**
     * 验证未注册路径的 WebSocket 升级请求被拒绝（不崩溃、不 hang）。
     * 覆盖 WebSocket 路由未命中时的行为。
     *
     * 价值：路由缺失导致连接 hang（超时 5s 才暴露）比直接拒绝更有害，需确保拒绝路径正常工作。
     */
    @Test
    void connectToInvalidPath_connectionFails() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> failed = new AtomicReference<>(false);

        wsClient = new OkHttpClient.Builder()
                .readTimeout(3, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url("ws://localhost:9090/ws/nonexistent").build();
        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
                failed.set(true);
                latch.countDown();
            }

            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                latch.countDown(); // 不应该到达
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "timeout waiting for connection result");
        assertTrue(failed.get(), "unregistered path should fail to connect");
    }

    /**
     * 验证多线程并发发送时帧不交织、不丢失。
     * 覆盖 {@code NettyWebSocketSession.sendMessage()} 的 EventLoop 串行化路径。
     *
     * 价值：并发发送是 P0 级别的修复，在 OkHttp 的 WebSocket 实现中，
     * send() 可从任意线程调用。无此测试则 EventLoop 串行化退化不可发现。
     */
    @Test
    void concurrentSends_allMessagesDelivered() throws Exception {
        int threadCount = 4;
        int messagesPerThread = 25;
        int totalMessages = threadCount * messagesPerThread;
        CountDownLatch latch = new CountDownLatch(totalMessages);
        java.util.List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();

        wsClient = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url("ws://localhost:9090/ws/echo").build();
        CountDownLatch openLatch = new CountDownLatch(1);

        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                openLatch.countDown();
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                received.add(text);
                latch.countDown();
            }

            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
                while (latch.getCount() > 0) latch.countDown();
            }
        });

        assertTrue(openLatch.await(5, TimeUnit.SECONDS), "connection not opened");

        // 多线程并发发送
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            executor.submit(() -> {
                for (int i = 0; i < messagesPerThread; i++) {
                    webSocket.send("t" + threadId + "-m" + i);
                }
            });
        }
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue(latch.await(15, TimeUnit.SECONDS), "timeout waiting for " + totalMessages + " messages, got " + received.size());
        assertEquals(totalMessages, received.size(), "all messages should be delivered");

        // 验证每个消息都有对应的回显
        for (int t = 0; t < threadCount; t++) {
            for (int i = 0; i < messagesPerThread; i++) {
                String expected = "t" + t + "-m" + i;
                assertTrue(received.contains(expected), "missing: " + expected);
            }
        }
    }

    /**
     * 验证多个独立连接互不干扰。
     *
     * 价值：WebSocketRoutingHandler 是 @ChannelHandler.Sharable，所有连接共享同一个
     * handler 实例。若 handler 错误地使用了实例状态而非连接状态，多连接会互相污染。
     */
    @Test
    void concurrentConnections_isolation() throws Exception {
        int connectionCount = 5;
        CountDownLatch allDone = new CountDownLatch(connectionCount);
        java.util.List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int i = 0; i < connectionCount; i++) {
            int connId = i;
            OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build();
            Request request = new Request.Builder().url("ws://localhost:9090/ws/echo").build();
            client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                    ws.send("conn" + connId);
                }

                @Override
                public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                    try {
                        assertEquals("conn" + connId, text,
                                "connection " + connId + " received wrong echo");
                    } catch (Throwable t) {
                        errors.add(t);
                    }
                    ws.close(1000, "done");
                }

                @Override
                public void onClosed(@NotNull WebSocket ws, int code, @NotNull String reason) {
                    client.dispatcher().executorService().shutdown();
                    allDone.countDown();
                }

                @Override
                public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
                    client.dispatcher().executorService().shutdown();
                    allDone.countDown();
                }
            });
        }

        assertTrue(allDone.await(10, TimeUnit.SECONDS), "not all connections completed");
        assertTrue(errors.isEmpty(), "isolation errors: " + errors);
    }

    /**
     * 验证同一连接上混合发送文本和二进制消息，两种类型均能正确回显。
     * 覆盖帧类型分发逻辑确保 Text/Binary 不走错分支。
     *
     * 价值：帧类型区分是 WebSocket 协议的基本能力，混合使用场景能暴露 instanceof 分支错误。
     */
    @Test
    void binaryAndTextMixed_bothReceived() throws Exception {
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<String> textReceived = new AtomicReference<>();
        AtomicReference<ByteString> binaryReceived = new AtomicReference<>();

        wsClient = new OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build();
        Request request = new Request.Builder().url("ws://localhost:9090/ws/echo").build();
        byte[] binaryPayload = "bin".getBytes(StandardCharsets.UTF_8);

        webSocket = wsClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
                ws.send("text-msg");
                ws.send(ByteString.of(binaryPayload));
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
                textReceived.set(text);
                latch.countDown();
            }

            @Override
            public void onMessage(@NotNull WebSocket ws, @NotNull ByteString bytes) {
                binaryReceived.set(bytes);
                latch.countDown();
            }

            @Override
            public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, Response response) {
                while (latch.getCount() > 0) latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS), "timeout waiting for mixed messages");
        assertEquals("text-msg", textReceived.get());
        assertArrayEquals(binaryPayload, binaryReceived.get().toByteArray());
    }
}
