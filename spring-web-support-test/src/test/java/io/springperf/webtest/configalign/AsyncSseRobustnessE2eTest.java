package io.springperf.webtest.configalign;

import io.springperf.web.core.async.stream.SseEmitter;
import io.springperf.web.core.metrics.CountingWebMetrics;
import io.springperf.web.core.metrics.WebMetrics;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 异步 / SSE <b>健壮性</b> E2E：补齐 {@link AsyncSseLifecycleE2eTest} 未覆盖的组合场景。
 * <p>
 * 与生命周期类的关系：生命周期类验证「引用归零 + 响应语义」，本类验证<b>机制组合与边界</b>—— pipelining × 流式、已提交流上的迟到错误、端到端背压、HEAD/HTTP1.0 × 异步流、SSE 字段线格式、
 * 超时状态码契约。
 * </p>
 * <p>
 * 两条例据贯穿全部用例：
 * </p>
 * <ol>
 * <li>客户端语义正确（内容完整/保序/不悬挂）；</li>
 * <li>{@link CountingWebMetrics#activeAsyncLifecycles()} 回到基线 + 服务端发送器无缺陷信号日志 （后者由 {@code @AfterEach} 把关：SSE
 * 双终止块等缺陷往往「客户端全绿、服务端日志报错」）。</li>
 * </ol>
 */
@SpringBootTest(classes = { ConfigAlignTestApp.class,
        AsyncSseRobustnessE2eTest.RobustnessConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.servlet.context-path=/")
class AsyncSseRobustnessE2eTest {

    /** 异步超时后的响应状态码契约（由本类 {@code asyncTimeout_*} 用例锁定）。 */
    private static final int ASYNC_TIMEOUT_STATUS = 503;

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(15)).build();

    @LocalServerPort
    int port;

    /**
     * 本 context 的计量组件：在飞异步生命周期计数取自它（{@link CountingWebMetrics#activeAsyncLifecycles()}）。
     * <p>
     * 这里**不再**读那个全局静态计数：全局计数把「归零」变成整个 JVM 的不变量，别的 context 有残留就污染本类 （实测：整仓 {@code clean test} 下 28 条级联误报 / 422.5s）。计数改由
     * {@link RobustnessConfig} 注册为容器 bean，框架优先取容器内 bean，故作用域 = 本 context。
     * </p>
     */
    @Autowired
    WebMetrics webMetrics;

    /** 在飞的异步生命周期数（语义同原先的全局读数，作用域改为本 context）。 */
    private int activeRequestRefs() {
        if (!(webMetrics instanceof CountingWebMetrics counting)) {
            throw new IllegalStateException("本类需要可读计量实现（CountingWebMetrics），实际装配为 " + webMetrics.getClass().getName());
        }
        return counting.activeAsyncLifecycles();
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private Response get(String path) throws Exception {
        return CLIENT.newCall(new Request.Builder().url(base() + path).build()).execute();
    }

    private Socket openSocket(int soTimeoutMillis) throws IOException {
        Socket s = new Socket("localhost", port);
        s.setSoTimeout(soTimeoutMillis);
        return s;
    }

    private void send(Socket s, String raw) throws IOException {
        s.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
        s.getOutputStream().flush();
    }

    /** 读到满足条件或超时为止（不要求 EOF——keep-alive 连接不会主动关）。 */
    private static String readUntil(Socket s, Predicate<String> done, int timeoutMillis) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        InputStream in = s.getInputStream();
        while (System.currentTimeMillis() < deadline) {
            int n;
            try {
                n = in.read(buf);
            } catch (SocketTimeoutException e) {
                break;
            }
            if (n < 0) {
                break;
            }
            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            if (done.test(sb.toString())) {
                break;
            }
        }
        return sb.toString();
    }

    private static String readAll(Socket s, int timeoutMillis) throws IOException {
        return readUntil(s, text -> false, timeoutMillis);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    /**
     * 异步持有者引用必须**归零**（轮询容忍写终结回调的异步投递延迟）。
     * <p>
     * 起点由 {@code @BeforeEach} 保证为 0，故此处只断言 0。此前用「等于用例内捕获的基线」 判据，在全量套件下会因上一用例的跨用例延迟收尾而误报（实测 expected 1 / actual 0：
     * 基线被污染而非真的残留）。
     * </p>
     */
    // 2026-09-25 实测补记：整仓 `clean test`（CI 同款）在**机器同时跑其它构建**时，本类出现 28 条级联失败、
    // 总耗时 422.5s（每个用例都把 15s 窗口等满）；同一天隔离复跑（-Dtest=AsyncSseRobustnessE2eTest）为
    // 32/32 通过、15.15s。即「引用迟迟不归零」是**负载阻滞**而非真泄漏，而「阻滞」与「永久不归零」在固定窗口
    // 内本就判不出差异：宁可整类红，也不要放宽窗口去掩盖真泄漏；遇到级联先隔离复跑再下结论。
    private void assertRefsBackTo(int baseline) throws Exception {
        // 窗口 15s：用例内任务本身 3s（见 webAsyncTask_explicitTimeout… 的场景注释），整包 + 高负载下
        // 该路径收尾实测会超过 5s（曾造成 27 条级联误报；窗口放大到 30s 后全绿、类总耗时仅 15.24s，
        // 说明平时根本不等待）。取任务时长 3s 的 5 倍留余量，同时保证「永久不归零」仍然失败——
        // 不掩盖真泄漏。
        long deadline = System.currentTimeMillis() + 15000;
        while (activeRequestRefs() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        leakedRefsSeen.set(activeRequestRefs() != 0);
        assertEquals(0, activeRequestRefs(), "场景结束后异步持有者引用应归零（用例起点基线 " + baseline + "，残留即未终结的异步生命周期）");
    }

    // ==================== 1. pipelining × 流式 ====================

    @Test
    void pipelining_sseThenDeferred_responsesInOrder_noHang() throws Exception {
        int baseline = activeRequestRefs();
        try (Socket s = openSocket(10000)) {
            // 同连接连续两个请求：SSE 流 + DeferredResult（不等第一个响应）
            send(s, "GET /e2e-rob/sse HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /e2e-rob/dr HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String all = readUntil(s, text -> text.contains("dr-ok"), 10000);
            assertTrue(all.contains("rob-sse-a") && all.contains("rob-sse-b"), "SSE 流两段都应送达，实际:\n" + all);
            assertTrue(all.contains("dr-ok"), "排队中的第二个请求必须被处理，实际:\n" + all);
            assertTrue(all.indexOf("rob-sse-b") < all.indexOf("dr-ok"), "响应必须保序：先写完 SSE 流，再写第二个响应（不得交叉），实际:\n" + all);
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void pipelining_twoSseStreams_bothCompleteInOrder() throws Exception {
        int baseline = activeRequestRefs();
        try (Socket s = openSocket(10000)) {
            send(s, "GET /e2e-rob/sse HTTP/1.1\r\nHost: localhost\r\n\r\n"
                    + "GET /e2e-rob/sse-b HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String all = readUntil(s, text -> occurrences(text, "rob-b-b") >= 1, 10000);
            assertTrue(all.contains("rob-sse-a") && all.contains("rob-sse-b"), "第一条流两段都应送达，实际:\n" + all);
            assertTrue(all.contains("rob-b-a") && all.contains("rob-b-b"), "第二条流两段都应送达（不得丢流/污染），实际:\n" + all);
            assertTrue(all.indexOf("rob-sse-b") < all.indexOf("rob-b-a"), "两条流的帧不得交叉：第一条流必须整体先于第二条，实际:\n" + all);
        }
        assertRefsBackTo(baseline);
    }

    // ==================== 2. 已提交流上的迟到错误 ====================

    @Test
    void sse_lateSendErrorAfterCommit_streamNotCorrupted_noHang() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/sse-late-error")) {
            // 首块已提交（响应头 + 第一段 SSE 帧已发出）
            String first = resp.body().source().readUtf8Line();
            assertNotNull(first, "应至少收到首段");
            // 之后业务线程在已提交响应上 sendError：设计要求是「不悬挂、不覆盖已提交流、
            // 不写第二个终止块」；客户端可能感知为异常截断或错误体，故只断言可读完/不悬挂。
            try {
                resp.body().string();
            } catch (IOException expectedTruncationOrClose) {
                // 允许：拒绝写出 → 连接关闭导致客户端读到截断
            }
        }
        assertRefsBackTo(baseline);
    }

    // ==================== 3. 端到端背压（慢消费者） ====================

    @Test
    void backpressure_slowConsumer_thenRecover_allChunksInOrder() throws Exception {
        int baseline = activeRequestRefs();
        // 总量约 120KB（> 写缓冲 high watermark 32KB），足以触发应用层背压；
        // 用小块数 + 小接收窗口是为了让「客户端暂停读取 → 服务端暂停写出」尽快成立，
        // 同时保证用例在合理时间内收尾（块数过大会让恢复过程被 TCP 窗口限速拖长）。
        int chunks = 120;
        try (Socket s = openSocket(30000)) {
            s.setReceiveBufferSize(4096); // 小接收窗口：把服务端写缓冲推过 high watermark
            send(s, "GET /e2e-rob/sse-big?n=" + chunks + " HTTP/1.1\r\nHost: localhost\r\n\r\n");
            // 故意不读 1.2s：服务端 isWritable=false，发送器必须暂停写出而不是丢帧
            Thread.sleep(1200);
            String all = readUntil(s, text -> occurrences(text, "bp[" + chunks + "]") >= 1, 30000);
            for (int i = 1; i <= chunks; i++) {
                assertTrue(all.contains("bp[" + i + "]"),
                        "慢消费者恢复后第 " + i + " 块缺失（背压期间不得丢数据/截断），实际收到 " + occurrences(all, "bp[") + " 块");
            }
            assertTrue(all.indexOf("bp[1]") < all.indexOf("bp[" + chunks + "]"), "顺序必须保持");
            assertTrue(all.contains("0\r\n\r\n"),
                    "背压恢复后流必须正常收尾（chunked 终止块存在），实际尾部:\n" + all.substring(Math.max(0, all.length() - 200)));
        }
        assertRefsBackTo(baseline);
    }

    // ==================== 4. HEAD × 异步 / 流式 ====================

    @Test
    void headRequest_sse_headersOnly_noBody_lifecycleTerminates() throws Exception {
        int baseline = activeRequestRefs();
        try (Socket s = openSocket(6000)) {
            send(s, "HEAD /e2e-rob/sse HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String resp = readAll(s, 6000);
            assertTrue(resp.startsWith("HTTP/1.1 200"), "实际:\n" + resp);
            assertFalse(resp.contains("rob-sse-a"), "HEAD 不得回 body（Content-Length 可保留真实长度），实际:\n" + resp);
            assertTrue(resp.toLowerCase().contains("content-type: text/event-stream"), "实际:\n" + resp);
        }
        // 关键：没有 body 写出也要能终结异步生命周期（否则 HEAD + SSE 会悬挂）
        assertRefsBackTo(baseline);
    }

    @Test
    void headRequest_deferredResult_noBody_lifecycleTerminates() throws Exception {
        int baseline = activeRequestRefs();
        try (Socket s = openSocket(6000)) {
            send(s, "HEAD /e2e-rob/dr HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String resp = readAll(s, 6000);
            assertTrue(resp.startsWith("HTTP/1.1 200"), "实际:\n" + resp);
            assertFalse(resp.contains("dr-ok"), "HEAD 不得回 body，实际:\n" + resp);
        }
        assertRefsBackTo(baseline);
    }

    // ==================== 5. HTTP/1.0 × SSE ====================

    @Test
    void http10_sse_closeDelimited_noChunkFraming_eventsDelivered() throws Exception {
        int baseline = activeRequestRefs();
        try (Socket s = openSocket(8000)) {
            send(s, "GET /e2e-rob/sse HTTP/1.0\r\nHost: localhost\r\n\r\n");
            String resp = readAll(s, 8000);
            assertTrue(resp.startsWith("HTTP/1.1 200"), "实际:\n" + resp);
            String lower = resp.toLowerCase();
            assertFalse(lower.contains("transfer-encoding: chunked"),
                    "HTTP/1.0 不得发 chunked 帧（1.0 客户端会把分块标记当 body），实际:\n" + resp);
            assertTrue(lower.contains("connection: close"), "close-delimited 必须显式 Connection: close，实际:\n" + resp);
            assertTrue(resp.contains("rob-sse-a") && resp.contains("rob-sse-b"), "SSE 两段都应送达，实际:\n" + resp);
            assertTrue(resp.indexOf("rob-sse-a") < resp.indexOf("rob-sse-b"), "顺序保持");
        }
        assertRefsBackTo(baseline);
    }

    // ==================== 6. SSE 字段线格式 ====================

    @Test
    void sse_fields_idEventRetryComment_exactWireFormat() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/sse-fields")) {
            assertEquals(200, resp.code());
            assertEquals("text/event-stream", resp.header("Content-Type"));
            String body = resp.body().string();
            assertTrue(body.contains("id:42\n"), "缺少 id 字段，实际:\n" + body);
            assertTrue(body.contains("event:greeting\n"), "缺少 event 字段，实际:\n" + body);
            assertTrue(body.contains("retry:1500\n"), "缺少 retry 字段（毫秒），实际:\n" + body);
            assertTrue(body.contains(":keep-alive\n"), "缺少注释行，实际:\n" + body);
            assertTrue(body.contains("data:hello\n\n"), "缺少 data 字段与事件终止空行，实际:\n" + body);
            assertTrue(
                    body.indexOf("id:42") < body.indexOf("event:greeting")
                            && body.indexOf("event:greeting") < body.indexOf("retry:1500")
                            && body.indexOf("retry:1500") < body.indexOf(":keep-alive")
                            && body.indexOf(":keep-alive") < body.indexOf("data:hello"),
                    "SSE 字段顺序必须为 id → event → retry → comment → data，实际:\n" + body);
        }
        assertRefsBackTo(baseline);
    }

    // ==================== 7. 超时 → 状态码契约 ====================

    @Test
    void asyncTimeout_statusContract_locked_lateResultDropped() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/dr-timeout")) {
            int code = resp.code();
            String body = resp.body().string();
            assertEquals(ASYNC_TIMEOUT_STATUS, code, "异步超时的状态码契约（锁定值见 ASYNC_TIMEOUT_STATUS），实际 " + code);
            assertFalse(body.contains("late-result"), "超时后迟到的结果不得写入响应，实际 body:\n" + body);
        }
        assertRefsBackTo(baseline);
    }

    // ==================== 8. Reactive：客户端断连必须取消上游订阅 ====================

    @Test
    void reactive_clientAbort_cancelsUpstreamSubscription() throws Exception {
        ReactiveUpstream.reset();
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/reactive")) {
            assertEquals(200, resp.code(), "reactive 返回值应被识别为流式（需 ReactiveAdapter 支持）");
            String first = resp.body().source().readUtf8Line();
            assertNotNull(first, "应收到首个事件");
        }
        // 客户端已断开：上游订阅必须在有限时间内被取消——否则每个断连客户端都会留下
        // 仍在运行的源（DB 游标/网络流/定时器），是典型的资源泄漏。
        long deadline = System.currentTimeMillis() + 5000;
        while (!ReactiveUpstream.isCancelled() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(ReactiveUpstream.isCancelled(), "客户端断开后必须取消上游订阅（否则源继续运行 → 资源泄漏）");
        assertRefsBackTo(baseline);
    }

    // ==================== 9. 异步错误路径 ====================

    @Test
    void callable_throwsException_500AndRefsBack() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/callable-fail")) {
            assertEquals(500, resp.code(), "Callable 抛异常应映射为 500");
            resp.body().string();
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void deferredResult_setErrorResult_500AndRefsBack() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/dr-error")) {
            assertEquals(500, resp.code(), "setErrorResult 应映射为 500");
            resp.body().string();
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void completionStage_failedFuture_500AndRefsBack() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/cs-fail")) {
            assertEquals(500, resp.code(), "CompletionStage 异常完成应映射为 500");
            resp.body().string();
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void webAsyncTask_explicitTimeout_statusCodeLocked() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/web-async-task")) {
            int code = resp.code();
            String body = resp.body().string();
            // 契约锁定（实测所得）：WebAsyncTask（Callable 路径）显式超时 → 503，
            // 与 DeferredResult 超时（同样 503，见 asyncTimeout_statusContract_locked…）一致。
            // 诊断信息一并带上：整包运行时曾出现"300ms 超时已触发、任务 3s 未完成，客户端却收到 200"，
            // 需要 body/headers 才能区分「迟到 200 抢先」与「客户端拿到池化连接上的旧响应」。
            assertEquals(503, code,
                    "WebAsyncTask 显式超时的状态码契约，实际 " + code + "；body=" + body + "；headers=" + resp.headers());
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void asyncTimeout_clientAlreadyGone_refsBack_noHang() throws Exception {
        int baseline = activeRequestRefs();
        Socket s = openSocket(3000);
        send(s, "GET /e2e-rob/dr-slow HTTP/1.1\r\nHost: localhost\r\n\r\n");
        // 超时（150ms）尚未触发时就断开：超时回调随后在已断开的连接上收尾
        s.close();
        assertRefsBackTo(baseline);
    }

    // ==================== 10. SSE 编码与幂等语义 ====================

    @Test
    void sse_plainData_omitsIdEventRetryFields() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/sse")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("data:rob-sse-a"), "实际:\n" + body);
            assertFalse(body.contains("id:"), "未指定 id 时不得写 id 字段，实际:\n" + body);
            assertFalse(body.contains("event:"), "未指定 event 时不得写 event 字段，实际:\n" + body);
            assertFalse(body.contains("retry:"), "未指定 retry 时不得写 retry 字段，实际:\n" + body);
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void sse_multilineData_continuationUsesDataPrefix() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/sse-multiline")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("data:line1\ndata:line2\n\n"), "多行数据必须用 \\ndata: 续行并以空行结束事件，实际:\n" + body);
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void sse_earlyEncode_allChunksDelivered() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/sse-early")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            for (int i = 1; i <= 3; i++) {
                assertTrue(body.contains("early-" + i), "earlyEncode 路径缺少第 " + i + " 块，实际:\n" + body);
            }
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void sse_fastSendThenComplete_noDataLoss() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/sse-fast")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("fast-1") && body.contains("fast-2"), "complete 早于 sender 初始化时不得丢数据，实际:\n" + body);
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void sse_duplicateComplete_and_sendAfterComplete_areIgnored() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/sse-idempotent")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("idem-1"), "首块应送达，实际:\n" + body);
            assertFalse(body.contains("idem-2"), "complete 之后的数据不得写出，实际:\n" + body);
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void sse_headers_contentTypeAndCacheControlExact() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/sse")) {
            assertEquals(200, resp.code());
            assertEquals("text/event-stream", resp.header("Content-Type"));
            assertEquals("no-cache", resp.header("Cache-Control"));
            resp.body().string();
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void sse_utf8Multibyte_acrossChunks_intact() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/sse-utf8")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("data:中文-1"), "多字节内容应原样送达，实际:\n" + body);
            assertTrue(body.contains("data:表情-🎯"), "emoji（代理对）不得跨帧截断，实际:\n" + body);
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void textStreamEmitter_rawTextWithoutSsePrefix() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/text-stream")) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("line-1") && body.contains("line-2"), "实际:\n" + body);
            assertFalse(body.contains("data:"), "text 流不应带 SSE 前缀，实际:\n" + body);
        }
        assertRefsBackTo(baseline);
    }

    // ==================== 11. 连接复用 / 断开组合 ====================

    @Test
    void keepAlive_sseThenNormalRequest_connectionReusable() throws Exception {
        int baseline = activeRequestRefs();
        try (Socket s = openSocket(10000)) {
            send(s, "GET /e2e-rob/sse HTTP/1.1\r\nHost: localhost\r\n\r\n");
            String first = readUntil(s, text -> text.contains("0\r\n\r\n"), 10000);
            assertTrue(first.contains("rob-sse-b"), "首条流应完整送出，实际:\n" + first);
            assertTrue(first.contains("0\r\n\r\n"), "首条流应有 chunked 终止块，实际:\n" + first);
            // 同连接复用：SSE 收尾不得污染 keep-alive 连接
            send(s, "GET /e2e-rob/dr HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            String second = readUntil(s, text -> text.contains("dr-ok"), 10000);
            assertTrue(second.contains("dr-ok"), "同连接第二个请求必须被处理，实际:\n" + second);
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void backpressure_slowConsumer_thenAbort_refsBack_noDefect() throws Exception {
        int baseline = activeRequestRefs();
        try (Socket s = openSocket(8000)) {
            s.setReceiveBufferSize(4096);
            send(s, "GET /e2e-rob/sse-big?n=200 HTTP/1.1\r\nHost: localhost\r\n\r\n");
            Thread.sleep(800); // 不读 → 服务端进入背压（不可写）
        } // 背压中客户端消失
        assertRefsBackTo(baseline);
    }

    @Test
    void clientAbort_beforeFirstChunk_refsBack_noHang() throws Exception {
        int baseline = activeRequestRefs();
        Socket s = openSocket(3000);
        send(s, "GET /e2e-rob/sse-slow-start HTTP/1.1\r\nHost: localhost\r\n\r\n");
        s.close(); // 首块送出之前就断开
        assertRefsBackTo(baseline);
    }

    @Test
    void http11_chunkedFraming_terminatorPresent() throws Exception {
        int baseline = activeRequestRefs();
        try (Socket s = openSocket(8000)) {
            send(s, "GET /e2e-rob/sse HTTP/1.1\r\nHost: localhost\r\n\r\n");
            String resp = readUntil(s, text -> text.contains("0\r\n\r\n"), 8000);
            assertTrue(resp.toLowerCase().contains("transfer-encoding: chunked"), "实际:\n" + resp);
            assertTrue(resp.contains("data:rob-sse-a"), "实际:\n" + resp);
            assertTrue(resp.contains("0\r\n\r\n"), "chunked 终止块缺失，实际:\n" + resp);
        }
        assertRefsBackTo(baseline);
    }

    // ==================== 12. Reactive 正常/异常终止 ====================

    @Test
    void reactive_sourceCompletes_streamTerminatesNormally_refsBack() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/reactive-complete")) {
            assertEquals(200, resp.code());
            String body = resp.body().string(); // 读到 EOF：onComplete → 正常收尾
            assertTrue(body.contains("rx-c[1]") && body.contains("rx-c[3]"), "实际:\n" + body);
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void reactive_sourceErrors_streamTruncated_refsBack() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/reactive-error")) {
            assertEquals(200, resp.code());
            try {
                resp.body().string();
            } catch (IOException expectedTruncation) {
                // onError → completeWithError：以截断示人（同 sse-error 语义）
            }
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void reactive_idleSource_clientAbort_cancelsUpstreamSubscription() throws Exception {
        ReactiveUpstream.reset();
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/reactive-idle")) {
            assertEquals(200, resp.code());
            String first = resp.body().source().readUtf8Line();
            assertNotNull(first, "应收到首个事件");
        }
        // 空闲源：断开后不再投递、也不发终止信号 —— 只能靠「断连钩子」取消。
        // 若只在「下次投递失败」时取消，此场景永远不会取消（源永久存留）。
        long deadline = System.currentTimeMillis() + 5000;
        while (!ReactiveUpstream.isCancelled() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(ReactiveUpstream.isCancelled(), "空闲源在客户端断开后也必须取消上游订阅（否则源永久存留）");
        assertRefsBackTo(baseline);
    }

    @Test
    void reactive_defaultSse_isJsonEncoding_objectPayloadSerialized() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/sse-json")) {
            assertEquals(200, resp.code());
            assertEquals("text/event-stream", resp.header("Content-Type"));
            String first = resp.body().source().readUtf8Line();
            assertNotNull(first, "应收到首个事件");
            // 结论（修正上一轮误判）：text/event-stream 的 reactive 流【默认】就是
            // SseJsonEmitter（createStreamEmitter 分支），对象载荷被 JSON 序列化；
            // 上一轮用 String 载荷看不出差别，是因为该 JsonConverter 对顶层字符串不加引号。
            assertTrue(first.contains("data:{\"v\":\"rx-j[1]\"}"), "默认应为 JSON 编码（SseJsonEmitter），实际首行: " + first);
        }
        assertRefsBackTo(baseline);
    }

    @Test
    void reactive_streamEmitterTypeAnnotation_selectsGivenEmitter() throws Exception {
        int baseline = activeRequestRefs();
        try (Response resp = get("/e2e-rob/sse-annotated")) {
            assertEquals(200, resp.code());
            String first = resp.body().source().readUtf8Line();
            assertNotNull(first, "应收到首个事件");
            // 注解生效证据：@ReactiveSupport(streamEmitterType = TextStreamEmitter.class) 命中
            // createStreamEmitter 的首个分支 → 不再输出 SSE 的 data: 帧，而是原样文本行。
            assertFalse(first.startsWith("data:"),
                    "@ReactiveSupport(streamEmitterType) 应选中指定 emitter（此处无 data: 前缀），实际: " + first);
            assertTrue(first.contains("rx-t["), "实际: " + first);
        }
        assertRefsBackTo(baseline);
    }

    // ==================== 服务端缺陷信号判据（同生命周期类） ====================

    private ch.qos.logback.classic.Logger streamLogLogger;
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> streamLogAppender;

    /**
     * 本轮是否已观测到「引用未归零」。一旦观测到，引用仍在的后续用例**必然失败**，此时再各等满 15s 只是把级联 拖长（实测 28 条 × 15s = 422.5s）；早失败不改变任何结论，只缩短反馈。
     */
    private static final java.util.concurrent.atomic.AtomicBoolean leakedRefsSeen = new java.util.concurrent.atomic.AtomicBoolean();

    @org.junit.jupiter.api.BeforeEach
    void waitForNoInFlightAsyncLifecycle() throws Exception {
        // 上一用例的异步终结可能晚于本用例开始（跨用例延迟）→ 先等到引用清零，使基线稳定；
        // 否则基线被上一用例污染（实测：基线=1、结束时=0 → 误报）。
        // 窗口 15s：上一用例的收尾在整包 + 高负载下可能迟到数秒，基线判定需给同等余量。
        // 恢复了就重新武装满窗口（下一个真泄漏照样有完整余量），没恢复则本用例不再重等。
        long deadline = System.currentTimeMillis() + (leakedRefsSeen.get() ? 0 : 15000);
        while (activeRequestRefs() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        leakedRefsSeen.set(activeRequestRefs() != 0);
        assertEquals(0, activeRequestRefs(), "用例开始前不应存在未终结的异步生命周期");
    }

    @org.junit.jupiter.api.BeforeEach
    void attachStreamLogCapture() {
        streamLogLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
                .getLogger("io.springperf.web.core.async.stream.AbstractNettyStreamSender");
        streamLogAppender = new ch.qos.logback.core.read.ListAppender<>();
        streamLogAppender.start();
        streamLogLogger.addAppender(streamLogAppender);
    }

    @org.junit.jupiter.api.AfterEach
    void assertNoServerSideStreamDefects() {
        // 前置 @BeforeEach 抛错时 attachStreamLogCapture 不会执行，而 @AfterEach 仍会跑：此处原先直接解引用，
        // 于是抛 NPE 并被 JUnit 记为「Suppressed」，把真正的断言失败信息盖住（实测：级联失败时 28 条都带它）。
        if (streamLogLogger == null || streamLogAppender == null) {
            return;
        }
        streamLogLogger.detachAppender(streamLogAppender);
        streamLogAppender.stop();
        java.util.List<String> problems = streamLogAppender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.ERROR)
                        || isDefectSignal(e.getFormattedMessage()))
                .map(e -> e.getLevel() + ": " + e.getFormattedMessage()).collect(java.util.stream.Collectors.toList());
        assertTrue(problems.isEmpty(), "服务端流式发送器出现缺陷信号（双终止块/编码器异常/终止块写失败），实际:\n" + problems);
    }

    /** 缺陷信号判定；「业务异常终止」的设计内 WARN 不算缺陷（见生命周期类同名方法说明）。 */
    private static boolean isDefectSignal(String message) {
        return message != null && (message.contains("write ERROR") || message.contains("EncoderException")
                || message.contains("unexpected message type") || message.contains("endStream failed"));
    }

    // ==================== 测试应用 ====================

    @TestConfiguration
    static class RobustnessConfig {

        /**
         * 可读计量实现：本类要断言「异步生命周期归零」，而默认装配是 {@code NoOpWebMetrics} —— 它读不出计数 （这正是默认装配零开销的原因）。注册成容器 bean 后框架优先取它。
         */
        @Bean
        WebMetrics countingWebMetrics() {
            return new CountingWebMetrics();
        }

        @Bean
        RobustnessController robustnessController() {
            return new RobustnessController();
        }

        /**
         * 自定义 {@link ReactiveAdapterRegistry}：本仓库不含 reactor，Spring 共享注册表无法适配 裸
         * {@code org.reactivestreams.Publisher}——若不自注册，reactive 返回值不会进入流式路径， 实测表现为「200 + 空 body」（静默降级，正是该路径长期无 E2E
         * 的根因）。 框架侧 {@code ReactiveReturnValueResolver.initWithWebContext} 优先取容器内该类型 bean， 故此处注册即可让
         * {@link ReactiveUpstream} 走完整「Publisher → SSE」链路。
         */
        @Bean
        org.springframework.core.ReactiveAdapterRegistry reactiveAdapterRegistry() {
            org.springframework.core.ReactiveAdapterRegistry registry = new org.springframework.core.ReactiveAdapterRegistry();
            registry.registerReactiveType(
                    org.springframework.core.ReactiveTypeDescriptor.multiValue(ReactiveUpstream.class, () -> null),
                    value -> (org.reactivestreams.Publisher<?>) value, publisher -> null);
            return registry;
        }
    }

    @RestController
    static class RobustnessController {

        @GetMapping("/e2e-rob/dr")
        public DeferredResult<String> deferred() {
            DeferredResult<String> result = new DeferredResult<>(5000L);
            result.setResult("dr-ok");
            return result;
        }

        /** 超时（150ms）后结果迟到（600ms）→ 用于锁定超时状态码契约。 */
        @GetMapping("/e2e-rob/dr-timeout")
        public DeferredResult<String> deferredTimeout() {
            DeferredResult<String> result = new DeferredResult<>(150L);
            Thread worker = new Thread(() -> {
                sleepQuietly(600);
                result.setResult("late-result");
            }, "e2e-rob-timeout-worker");
            worker.setDaemon(true);
            worker.start();
            return result;
        }

        @GetMapping(value = "/e2e-rob/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter sse() {
            return streaming(emitter -> {
                emitter.send("rob-sse-a");
                sleepQuietly(30);
                emitter.send("rob-sse-b");
            });
        }

        @GetMapping(value = "/e2e-rob/sse-b", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter sseB() {
            return streaming(emitter -> {
                emitter.send("rob-b-a");
                sleepQuietly(30);
                emitter.send("rob-b-b");
            });
        }

        /**
         * 已提交后再注入迟到错误：先发一段（响应头 + 首帧已提交），随后从 worker 线程调用 {@code sendError} —— 即「已提交响应上的错误体写入」路径（本次修复的 Leak A 触发点）。
         */
        @GetMapping(value = "/e2e-rob/sse-late-error", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter sseLateError(HttpServletResponse response) {
            SseEmitter emitter = new SseEmitter();
            Thread worker = new Thread(() -> {
                try {
                    emitter.send("late-1");
                    sleepQuietly(150);
                    response.sendError(500, "late-error");
                    sleepQuietly(100);
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }, "e2e-rob-late-error-worker");
            worker.setDaemon(true);
            worker.start();
            return emitter;
        }

        @GetMapping(value = "/e2e-rob/sse-fields", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter sseFields() {
            return streaming(emitter -> emitter.send(ServerSentEvent.builder().id("42").event("greeting")
                    .retry(Duration.ofMillis(1500)).comment("keep-alive").data("hello").build()));
        }

        /** 大流量流：用于慢消费者端到端背压（每块约 1KB，块序号形如 {@code bp[i]}）。 */
        @GetMapping(value = "/e2e-rob/sse-big", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter sseBig(@RequestParam(defaultValue = "300") int n) {
            return streaming(emitter -> {
                String padding = "x".repeat(1000);
                for (int i = 1; i <= n; i++) {
                    emitter.send("bp[" + i + "]" + padding);
                }
            });
        }

        /** Reactive 流：返回 reactive-streams Publisher，用于验证断连时的上游取消订阅。 */
        @GetMapping(value = "/e2e-rob/reactive", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public org.reactivestreams.Publisher<Object> reactive() {
            return new ReactiveUpstream();
        }

        @GetMapping("/e2e-rob/callable-fail")
        public java.util.concurrent.Callable<String> callableFail() {
            return () -> {
                throw new IllegalStateException("callable-boom");
            };
        }

        @GetMapping("/e2e-rob/dr-error")
        public DeferredResult<String> drError() {
            DeferredResult<String> result = new DeferredResult<>(5000L);
            result.setErrorResult(new IllegalStateException("dr-boom"));
            return result;
        }

        @GetMapping("/e2e-rob/cs-fail")
        public java.util.concurrent.CompletableFuture<String> csFail() {
            return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("cs-boom"));
        }

        /**
         * WebAsyncTask 显式 150ms 超时，Callable 睡 1s 且**不响应中断** → 超时收尾。
         * <p>
         * 为什么必须"不响应中断"：普通 {@code sleepQuietly} 会吞掉超时触发的 interrupt 并立刻 返回 {@code late-task}，于是"超时 503"与"被中断后提前完成
         * 200"成了竞态 （整包运行时实测 body=late-task/200，耗时仅 0.32s）。让 Callable 睡满后， 超时路径成为唯一可能的结果，契约（超时 → 503）被确定性地锁定。
         * </p>
         */
        @GetMapping("/e2e-rob/web-async-task")
        public org.springframework.web.context.request.async.WebAsyncTask<String> webAsyncTask() {
            return new org.springframework.web.context.request.async.WebAsyncTask<>(150L, () -> {
                sleepUninterruptibly(1000);
                return "late-task";
            });
        }

        /** 睡满指定时长并忽略中断：供"超时必须是唯一结果"的夹具使用。 */
        private static void sleepUninterruptibly(long millis) {
            long end = System.nanoTime() + millis * 1_000_000L;
            while (true) {
                long left = end - System.nanoTime();
                if (left <= 0) {
                    return;
                }
                try {
                    Thread.sleep(Math.max(1L, left / 1_000_000L));
                } catch (InterruptedException ignored) {
                    // 继续睡满（见调用处说明）
                }
            }
        }

        /** 150ms 超时 + 800ms 迟到结果：供「超时回调时客户端已断开」用例使用。 */
        @GetMapping("/e2e-rob/dr-slow")
        public DeferredResult<String> drSlow() {
            DeferredResult<String> result = new DeferredResult<>(150L);
            Thread worker = new Thread(() -> {
                sleepQuietly(800);
                result.setResult("dr-slow-late");
            }, "e2e-rob-dr-slow-worker");
            worker.setDaemon(true);
            worker.start();
            return result;
        }

        @GetMapping(value = "/e2e-rob/sse-multiline", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter sseMultiline() {
            return streaming(emitter -> emitter.send("line1\nline2"));
        }

        /** earlyEncode=true：App 线程早编码路径。 */
        @GetMapping(value = "/e2e-rob/sse-early", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter sseEarly() {
            SseEmitter emitter = new SseEmitter(true);
            Thread worker = new Thread(() -> {
                try {
                    for (int i = 1; i <= 3; i++) {
                        emitter.send("early-" + i);
                    }
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }, "e2e-rob-sse-early-worker");
            worker.setDaemon(true);
            worker.start();
            return emitter;
        }

        /** 同步极速发送后立即 complete：覆盖「complete 早于 sender 初始化」竞态。 */
        @GetMapping(value = "/e2e-rob/sse-fast", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter sseFast() {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send("fast-1");
                emitter.send("fast-2");
            } catch (Exception e) {
                emitter.completeWithError(e);
                return emitter;
            }
            emitter.complete();
            return emitter;
        }

        /** 幂等语义：重复 complete / 完成后 send 不得改变已终止的流。 */
        @GetMapping(value = "/e2e-rob/sse-idempotent", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter sseIdempotent() {
            return streaming(emitter -> {
                emitter.send("idem-1");
                emitter.complete();
                emitter.complete();
                emitter.completeWithError(new IllegalStateException("late"));
                emitter.send("idem-2");
            });
        }

        /** UTF-8 多字节内容跨帧完整性（含 emoji 代理对）。 */
        @GetMapping(value = "/e2e-rob/sse-utf8", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter sseUtf8() {
            return streaming(emitter -> {
                emitter.send("中文-1");
                sleepQuietly(20);
                emitter.send("表情-🎯");
            });
        }

        @GetMapping(value = "/e2e-rob/text-stream", produces = MediaType.TEXT_PLAIN_VALUE)
        public io.springperf.web.core.async.stream.TextStreamEmitter textStream() {
            io.springperf.web.core.async.stream.TextStreamEmitter emitter = new io.springperf.web.core.async.stream.TextStreamEmitter();
            Thread worker = new Thread(() -> {
                try {
                    emitter.send("line-1\n");
                    emitter.send("line-2\n");
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }, "e2e-rob-text-stream-worker");
            worker.setDaemon(true);
            worker.start();
            return emitter;
        }

        /** 首块延迟 500ms：供「首块之前客户端断开」用例使用。 */
        @GetMapping(value = "/e2e-rob/sse-slow-start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter sseSlowStart() {
            return streaming(emitter -> {
                sleepQuietly(500);
                emitter.send("slow-start-1");
            });
        }

        /** Reactive：投递 3 条后 onComplete（正常收尾）。 */
        @GetMapping(value = "/e2e-rob/reactive-complete", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public org.reactivestreams.Publisher<Object> reactiveComplete() {
            return new ReactiveUpstream(3, false, "rx-c");
        }

        /** Reactive：投递 2 条后 onError（截断收尾）。 */
        @GetMapping(value = "/e2e-rob/reactive-error", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public org.reactivestreams.Publisher<Object> reactiveError() {
            return new ReactiveUpstream(2, true, "rx-e");
        }

        /** Reactive 空闲源：投递 1 条后挂住（不发终止信号），用于验证断连取消。 */
        @GetMapping(value = "/e2e-rob/reactive-idle", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public org.reactivestreams.Publisher<Object> reactiveIdle() {
            return new ReactiveUpstream(1, false, "rx-idle", true);
        }

        /**
         * JSON 线格式：text/event-stream 的 reactive 流默认即由框架构造 {@code SseJsonEmitter} （对象载荷会被 JSON 序列化）。
         */
        @GetMapping(value = "/e2e-rob/sse-json", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public org.reactivestreams.Publisher<Object> sseJson() {
            return new ReactiveUpstream(1, false, "rx-j", false, true);
        }

        /**
         * 注解选择 emitter：{@code @ReactiveSupport(streamEmitterType = TextStreamEmitter.class)} 应命中 createStreamEmitter
         * 的首个分支（按注解构造），输出不再是 SSE 的 {@code data:} 帧。
         */
        @io.springperf.web.annotation.ReactiveSupport(streamEmitterType = io.springperf.web.core.async.stream.TextStreamEmitter.class)
        @GetMapping(value = "/e2e-rob/sse-annotated", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public org.reactivestreams.Publisher<Object> sseAnnotated() {
            return new ReactiveUpstream(1, false, "rx-t");
        }

        // ---- 辅助 ----

        private interface EmitterJob {
            void run(SseEmitter emitter) throws Exception;
        }

        /** 启动 daemon worker 执行发送并在结束时 complete（异常则 completeWithError）。 */
        private static SseEmitter streaming(EmitterJob job) {
            SseEmitter emitter = new SseEmitter();
            Thread worker = new Thread(() -> {
                try {
                    job.run(emitter);
                    emitter.complete();
                } catch (Exception e) {
                    emitter.completeWithError(e);
                }
            }, "e2e-rob-sse-worker");
            worker.setDaemon(true);
            worker.start();
            return emitter;
        }

        private static void sleepQuietly(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 测试用上游源：持续投递并在 {@code cancel()} 时置标志。
     * <p>
     * 用 {@code org.reactivestreams} 而非 reactor（本仓库无 reactor 依赖）；投递放在 {@code request(n)} 触发的独立线程中，模拟真实源（DB 游标/网络流）异步产出。
     * </p>
     */
    static final class ReactiveUpstream implements org.reactivestreams.Publisher<Object> {

        private static final java.util.concurrent.atomic.AtomicBoolean CANCELLED = new java.util.concurrent.atomic.AtomicBoolean();

        /** -1 = 持续投递直到被取消；>=0 = 投递指定条数后按 {@link #error} 决定 onComplete/onError。 */
        private final int chunks;
        private final boolean error;
        private final String prefix;
        /** true：投递完 chunks 后既不 onComplete 也不 onError，只是挂住（模拟空闲长轮询源）。 */
        private final boolean idle;
        /** true：投递 Map 载荷（用于验证 JSON 序列化），否则投递字符串。 */
        private final boolean jsonObject;

        ReactiveUpstream() {
            this(-1, false, "rx");
        }

        ReactiveUpstream(int chunks, boolean error, String prefix) {
            this(chunks, error, prefix, false, false);
        }

        ReactiveUpstream(int chunks, boolean error, String prefix, boolean idle) {
            this(chunks, error, prefix, idle, false);
        }

        ReactiveUpstream(int chunks, boolean error, String prefix, boolean idle, boolean jsonObject) {
            this.chunks = chunks;
            this.error = error;
            this.prefix = prefix;
            this.idle = idle;
            this.jsonObject = jsonObject;
        }

        static void reset() {
            CANCELLED.set(false);
        }

        static boolean isCancelled() {
            return CANCELLED.get();
        }

        @Override
        public void subscribe(org.reactivestreams.Subscriber<? super Object> subscriber) {
            subscriber.onSubscribe(new org.reactivestreams.Subscription() {
                private final java.util.concurrent.atomic.AtomicLong remaining = new java.util.concurrent.atomic.AtomicLong();
                private final java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();

                @Override
                public void request(long n) {
                    remaining.addAndGet(n);
                    Thread producer = new Thread(() -> {
                        int sent = 0;
                        while (!stopped.get() && remaining.get() > 0) {
                            if (chunks >= 0 && sent >= chunks) {
                                break;
                            }
                            remaining.decrementAndGet();
                            sent++;
                            Object payload = jsonObject ? java.util.Map.of("v", prefix + "[" + sent + "]")
                                    : prefix + "[" + sent + "]";
                            subscriber.onNext(payload);
                            AsyncSseRobustnessE2eTest.RobustnessController.sleepQuietly(30);
                        }
                        if (idle) {
                            // 空闲源：不再投递、也不发终止信号，只能靠「断连钩子」取消
                            while (!stopped.get()) {
                                AsyncSseRobustnessE2eTest.RobustnessController.sleepQuietly(50);
                            }
                            return;
                        }
                        if (!stopped.get() && chunks >= 0 && sent >= chunks) {
                            if (error) {
                                subscriber.onError(new IllegalStateException("reactive-boom"));
                            } else {
                                subscriber.onComplete();
                            }
                        }
                    }, "e2e-rob-rx-upstream");
                    producer.setDaemon(true);
                    producer.start();
                }

                @Override
                public void cancel() {
                    stopped.set(true);
                    CANCELLED.set(true);
                }
            });
        }
    }
}
