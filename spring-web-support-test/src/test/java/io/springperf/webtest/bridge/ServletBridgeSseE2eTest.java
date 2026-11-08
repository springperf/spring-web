package io.springperf.webtest.bridge;

import io.springperf.web.core.metrics.CountingWebMetrics;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.webtest.BaseE2ETest;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * servlet 桥接模式下的 SSE E2E（补齐 {@link ServletBridgeE2eTest} 未覆盖的流式/异步路径）。
 * <p>
 * 桥接模式此前只有 servlet 通用语义用例（requestURL/redirect/session/forward/include）， 流式路径完全未测——而桥接模式经
 * {@code SupportDispatcherHandler} + servlet 响应包装， 与 native 模式的提交/终止路径并不完全相同（Writer 编码缓冲、flushBuffer 渐进式提交、 session
 * 写完成持久化）。本用例验证：SSE 事件送达、流被正常终止（客户端读到 EOF）、 且异步持有者引用归零。
 * </p>
 * <p>
 * 本类同时覆盖 Spring 兼容类型：mvc {@code SseEmitter}（extends ResponseBodyEmitter extends StreamEmitter）与
 * {@code ResponseBodyEmitter} —— 二者经 {@code ResponseBodyEmitterReturnValueResolver} 注入
 * encodeFunction（{@code AdapterUtil}） 后走同一 native 内核，桥接模式下必须与 native 语义一致。
 * </p>
 */
class ServletBridgeSseE2eTest extends BaseE2ETest {

    /**
     * 本 context 的计量组件（由 {@code SupportTestApplication} 注册 {@link CountingWebMetrics}）：在飞异步生命周期
     * 计数取自它，而不再是全局静态字段——全局读数会被别的 context 的残留污染。
     */
    @Autowired
    WebMetrics webMetrics;

    /** 在飞的异步生命周期数（语义同原先的全局读数，作用域为本 context）。 */
    private int activeRequestRefs() {
        if (!(webMetrics instanceof CountingWebMetrics counting)) {
            throw new IllegalStateException("本类需要可读计量实现（CountingWebMetrics），实际装配为 " + webMetrics.getClass().getName());
        }
        return counting.activeAsyncLifecycles();
    }

    @Test
    void sse_servletBridge_deliversEventsAndTerminates_refsReturnToBaseline() throws Exception {
        int baseline = activeRequestRefs();
        Request request = new Request.Builder().url(url("/api/servlet-bridge/sse-stream")).build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertEquals(200, resp.code());
            String contentType = resp.header("Content-Type");
            assertNotNull(contentType, "SSE 响应必须带 Content-Type");
            assertTrue(contentType.contains("text/event-stream"),
                    "桥接模式 SSE 必须协商为 text/event-stream，实际: " + contentType);
            // 读到 EOF：证明流被正常终止（终止块存在），而不是挂到读超时
            String body = resp.body().string();
            assertTrue(body.contains("data:bridge-a"), "应收到首个事件，实际:\n" + body);
            assertTrue(body.contains("data:bridge-b"), "应收到第二个事件，实际:\n" + body);
            assertTrue(body.indexOf("bridge-a") < body.indexOf("bridge-b"), "事件顺序必须保持，实际:\n" + body);
        }
        // 桥接模式同样受异步引用计数不变式约束（endStream/complete 后必须归零）
        long deadline = System.currentTimeMillis() + 5000;
        while (activeRequestRefs() != baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(baseline, activeRequestRefs(), "桥接模式 SSE 结束后异步持有者引用应归零（偏离 " + baseline + " 说明有未终结的异步生命周期）");
    }

    @Test
    void sse_servletBridgeBusinessError_truncated_refsReturnToBaseline() throws Exception {
        int baseline = activeRequestRefs();
        boolean truncated = false;
        try (Response resp = CLIENT.newCall(new Request.Builder().url(url("/api/servlet-bridge/sse-error")).build())
                .execute()) {
            assertEquals(200, resp.code());
            // 首段断言必须在 catch 之外：IOException 不得跳过唯一的内容校验（同 AsyncSseLifecycleE2eTest 语义）
            String first = resp.body().source().readUtf8Line();
            assertNotNull(first, "异常终止前首段应已送达");
            assertTrue(first.contains("bridge-err-1"), "首段内容应为 bridge-err-1，实际: " + first);
            try {
                resp.body().string();
            } catch (java.io.IOException expectedTruncation) {
                // 业务异常终止不写 LastHttpContent，客户端感知为截断
                truncated = true;
            }
        }
        assertTrue(truncated, "业务异常终止必须让客户端读到截断的 chunked 流（不得写 LastHttpContent）");
        long deadline = System.currentTimeMillis() + 5000;
        while (activeRequestRefs() != baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(baseline, activeRequestRefs(), "桥接模式 SSE 异常终止后异步持有者引用应归零（偏离 " + baseline + "）");
    }

    /**
     * Spring 兼容的 mvc {@code SseEmitter}：桥接模式下应产出标准 SSE 帧并正常终止。 与 native {@code SseEmitter} 的区别在于 encodeFunction 由
     * {@code AdapterUtil} 注入 （{@code ResponseBodyEmitterReturnValueResolver.preInitializeEmitter}）。
     */
    @Test
    void mvcSseEmitter_framesDeliveredAndTerminated_refsToZero() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = CLIENT.newCall(new Request.Builder().url(url("/api/servlet-bridge/sse-mvc")).build())
                .execute()) {
            assertEquals(200, resp.code());
            String contentType = resp.header("Content-Type");
            assertNotNull(contentType, "SSE 响应必须带 Content-Type");
            assertTrue(contentType.contains("text/event-stream"),
                    "mvc SseEmitter 必须协商为 text/event-stream，实际: " + contentType);
            String body = resp.body().string(); // 读到 EOF：终止块存在
            assertTrue(body.contains("data:mvc-a"), "应收到首帧，实际:\n" + body);
            assertTrue(body.contains("data:mvc-b"), "应收到次帧，实际:\n" + body);
            assertTrue(body.indexOf("mvc-a") < body.indexOf("mvc-b"), "顺序必须保持，实际:\n" + body);
        }
        assertRefsZeroed();
    }

    /**
     * Spring 兼容的 {@code ResponseBodyEmitter}（非 SSE）：经 codec 编码顺序写出，且正常终止。
     */
    @Test
    void responseBodyEmitter_encodesInOrderAndTerminates_refsToZero() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = CLIENT.newCall(new Request.Builder().url(url("/api/servlet-bridge/emitter")).build())
                .execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string(); // 读到 EOF：发送器写出终止块
            assertTrue(body.contains("emitter-1"), "应收到第一段，实际:\n" + body);
            assertTrue(body.contains("emitter-2"), "应收到第二段，实际:\n" + body);
            assertTrue(body.indexOf("emitter-1") < body.indexOf("emitter-2"), "顺序必须保持，实际:\n" + body);
        }
        assertRefsZeroed();
    }

    /**
     * {@code StreamingResponseBody}：由 {@code StreamingResponseBodyReturnValueResolver} 认领后正常流式写出。
     * <p>
     * 此前该类型无 resolver → 协商出 text/plain 却无 converter → 500。现在异步生命周期复用 Callable 通道、
     * 帧写出复用 StreamSender（与 SSE / Flux 同一条路径）：200 + 声明的 Content-Type + 分帧送达，
     * 客户端读到 EOF 说明终止块已写。
     * </p>
     */
    @Test
    void streamingResponseBody_streamsFramesAndTerminates() throws Exception {
        try (Response resp = CLIENT
                .newCall(new Request.Builder().url(url("/api/servlet-bridge/streaming-response-body")).build())
                .execute()) {
            String body = resp.body().string();
            assertEquals(200, resp.code(), "StreamingResponseBody 应被正常流式写出，实际 body=[" + body + "]");
            assertEquals("srb-1srb-2", body, "帧内容与顺序必须保持");
            String contentType = resp.header("Content-Type");
            assertNotNull(contentType, "produces 声明的 Content-Type 必须写出");
            assertTrue(contentType.contains("text/plain"), "实际: " + contentType);
        }
        assertRefsZeroed();
    }

    /**
     * 大流量：逐帧校验「帧号连续 + 内容未错位」。
     * <p>
     * 端点每帧前 4 字节写大端帧号，其余填充 {@code 'x'}。若帧顺序错乱、丢帧，或复用同一缓冲导致
     * 内容被后续写入覆盖，帧号或填充字节会在这里对不上。
     * </p>
     */
    @Test
    void streamingResponseBody_largePayloadIsFramedAndComplete() throws Exception {
        int frames = 200;
        try (Response resp = CLIENT.newCall(new Request.Builder()
                .url(url("/api/servlet-bridge/streaming-response-body-large?frames=" + frames)).build()).execute()) {
            byte[] body = resp.body().bytes();
            assertEquals(200, resp.code());
            assertEquals(frames * 1000, body.length, "大流量必须逐帧完整送达");
            for (int i = 0; i < frames; i++) {
                int offset = i * 1000;
                int sequence = ((body[offset] & 0xff) << 24) | ((body[offset + 1] & 0xff) << 16)
                        | ((body[offset + 2] & 0xff) << 8) | (body[offset + 3] & 0xff);
                assertEquals(i, sequence, "第 " + i + " 帧的帧号错位（帧序错乱或缓冲复用覆盖）");
                assertEquals('x', body[offset + 4], "第 " + i + " 帧填充内容被破坏");
                assertEquals('x', body[offset + 999], "第 " + i + " 帧尾部内容被破坏");
            }
        }
        assertRefsZeroed();
    }

    /** {@code ResponseEntity<StreamingResponseBody>}：状态码与自定义头必须生效。 */
    @Test
    void streamingResponseBody_responseEntityAppliesStatusAndHeaders() throws Exception {
        try (Response resp = CLIENT
                .newCall(new Request.Builder().url(url("/api/servlet-bridge/streaming-response-body-entity")).build())
                .execute()) {
            assertEquals(201, resp.code());
            assertEquals("yes", resp.header("X-Srb"));
            assertEquals("entity-srb", resp.body().string());
        }
        assertRefsZeroed();
    }

    /** 未声明 {@code produces} 时不得替用户编造 Content-Type。 */
    @Test
    void streamingResponseBody_withoutProducesDoesNotInventContentType() throws Exception {
        try (Response resp = CLIENT.newCall(
                new Request.Builder().url(url("/api/servlet-bridge/streaming-response-body-no-produces")).build())
                .execute()) {
            assertEquals(200, resp.code());
            assertEquals("no-produces", resp.body().string());
            assertNull(resp.header("Content-Type"), "未声明 produces 时不得编造 Content-Type");
        }
        assertRefsZeroed();
    }

    /** 0 字节：必须写终止块，客户端读到 EOF（空 body）而不是挂到读超时。 */
    @Test
    void streamingResponseBody_emptyStreamTerminatesWithoutHanging() throws Exception {
        try (Response resp = CLIENT.newCall(
                new Request.Builder().url(url("/api/servlet-bridge/streaming-response-body-empty")).build()).execute()) {
            assertEquals(200, resp.code());
            assertEquals("", resp.body().string(), "0 字节流必须写出终止块（读到 EOF 即空 body），否则会挂到读超时");
        }
        assertRefsZeroed();
    }

    /** HEAD：只发响应头，不得返回 body，也不得因多次 flush 打出 WARN 洪水。 */
    @Test
    void streamingResponseBody_headRequestSendsHeadersWithoutBody() throws Exception {
        try (Response resp = CLIENT.newCall(
                new Request.Builder().url(url("/api/servlet-bridge/streaming-response-body")).head().build())
                .execute()) {
            assertEquals(200, resp.code());
            String contentType = resp.header("Content-Type");
            assertNotNull(contentType, "HEAD 仍需给出与 GET 一致的 Content-Type");
            assertTrue(contentType.contains("text/plain"), "实际: " + contentType);
            assertEquals("", resp.body().string(), "HEAD 不得返回 body");
        }
        assertRefsZeroed();
    }

    /**
     * 写出任何字节之前失败：头已提交（chunked），客户端必然看到 200，随后只能截断连接。
     * <p>
     * 这是与 Spring MVC 的唯一行为差异（Spring 有 Servlet 容器兜着未提交状态，能改写成 500）。
     * 用例固化「绝不伪装成正常结束」——若换成写终止块，客户端会把截断当成 200 完整响应。
     * </p>
     */
    @Test
    void streamingResponseBody_throwsBeforeFirstFrame_truncatesConnection() throws Exception {
        boolean truncated = false;
        try (Response resp = CLIENT.newCall(
                new Request.Builder().url(url("/api/servlet-bridge/streaming-response-body-throw-before")).build())
                .execute()) {
            assertEquals(200, resp.code(), "flushChunked 已提交响应头，客户端必然看到 200");
            try {
                resp.body().string();
            } catch (java.io.IOException expectedTruncation) {
                truncated = true;
            }
        }
        assertTrue(truncated, "写前失败必须截断连接，不得写终止块伪装成 200 正常结束");
        assertRefsZeroed();
    }

    /** 写出若干帧之后失败：已送达的前缀保留，连接截断，不补「200 正常结束」的尾巴。 */
    @Test
    void streamingResponseBody_throwsAfterFrames_deliversPrefixThenTruncates() throws Exception {
        java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
        boolean truncated = false;
        try (Response resp = CLIENT.newCall(
                new Request.Builder().url(url("/api/servlet-bridge/streaming-response-body-throw-mid")).build())
                .execute()) {
            assertEquals(200, resp.code());
            try {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = resp.body().source().read(buffer)) != -1) {
                    received.write(buffer, 0, read);
                }
            } catch (java.io.IOException expectedTruncation) {
                truncated = true;
            }
        }
        assertTrue(truncated, "中途失败必须截断连接（不得写终止块），否则客户端无法感知失败");
        assertTrue(received.size() >= 8192, "失败前已入队的帧应已送达，实际收到 " + received.size() + " 字节");
        for (byte b : received.toByteArray()) {
            assertEquals('y', b, "已送达前缀的内容不得被破坏");
        }
        assertRefsZeroed();
    }

    /**
     * 客户端提前断开：服务端必须尽快感知并释放异步生命周期。
     * <p>
     * 端点会写满约 10s；断言在 5s 内引用归零。若断连后仍在把剩余帧「写完」（全部丢弃、CPU 空转），
     * 或卡在背压自旋里，这里会超时失败。
     * </p>
     */
    @Test
    void streamingResponseBody_clientDisconnects_asyncLifecycleIsReleased() throws Exception {
        int baseline = activeRequestRefs();
        Response resp = CLIENT.newCall(
                new Request.Builder().url(url("/api/servlet-bridge/streaming-response-body-slow")).build())
                .execute();
        assertEquals(200, resp.code());
        // 读到首批数据即断开：后续帧的写出必须因断连失败
        resp.body().source().readByteArray(100);
        // 流进行中必须真的持有引用，否则本类所有「归零」断言都是空转
        assertTrue(activeRequestRefs() > baseline,
                "流式进行中应持有异步引用，实际 " + activeRequestRefs() + "（=基线 " + baseline + "）");
        resp.close();

        long deadline = System.currentTimeMillis() + 5000;
        while (activeRequestRefs() != baseline && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(baseline, activeRequestRefs(),
                "客户端断开后异步生命周期必须尽快释放（残留即说明线程卡在背压等待，或仍在空写剩余帧）");
    }

    /** 异步持有者引用归零（桥接模式的异步生命周期同样受此不变式约束）。 */
    private void assertRefsZeroed() throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (activeRequestRefs() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(0, activeRequestRefs(), "场景结束后异步持有者引用应归零（残留即有未终结的异步生命周期）");
    }
}
