package io.springperf.webtest.bridge;

import io.springperf.web.core.metrics.CountingWebMetrics;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.webtest.BaseE2ETest;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        try (Response resp = CLIENT.newCall(new Request.Builder().url(url("/api/servlet-bridge/sse-error")).build())
                .execute()) {
            assertEquals(200, resp.code());
            try {
                String body = resp.body().string();
                assertTrue(body.contains("bridge-err-1"), "首段应送达，实际:\n" + body);
            } catch (java.io.IOException expectedTruncation) {
                // 异常终止 = 以截断示人（与 native 语义一致）
            }
        }
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
     * {@code StreamingResponseBody} 当前**不受支持**（行为固化用例）。
     * <p>
     * 它与 emitter 无关：只是独立 {@code @FunctionalInterface}（{@code writeTo(OutputStream)}）， 既不是 {@code StreamEmitter} 也没有
     * resolver 认领 → 返回值被静默忽略，客户端拿到 200 + 空 body。 若将来实现了对应 resolver（或改为 fail-fast 报错），本用例会失败并提示更新——这正是它的作用。
     * </p>
     */
    @Test
    void streamingResponseBody_isNotSupported_documentsGap() throws Exception {
        try (Response resp = CLIENT
                .newCall(new Request.Builder().url(url("/api/servlet-bridge/streaming-response-body")).build())
                .execute()) {
            assertEquals(200, resp.code(), "当前实现下应为 200（无 resolver 认领 → 静默空响应）");
            String body = resp.body().string();
            assertFalse(body.contains("srb-1"), "若此处出现内容，说明 StreamingResponseBody 已被支持——请把本用例改为正向断言。实际:\n" + body);
        }
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
