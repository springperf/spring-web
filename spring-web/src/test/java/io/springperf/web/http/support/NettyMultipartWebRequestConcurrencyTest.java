package io.springperf.web.http.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;

/**
 * {@link NettyMultipartWebRequest#parseIfNeeded()} 的并发回归。
 * <p>
 * 背景：解析有两个入口，而它们的<b>调用方线程路径不同</b>——{@code NettyServerHttpRequest.parseParameters} 在 {@code synchronized(request)} 内调
 * {@code getParameters()}（{@code @RequestParam}）， 而 {@code getMultiFileMap()} /
 * {@code getPartMap()}（{@code @RequestPart} / {@code MultipartFile}）<b>完全无锁</b>。 当 EventLoop
 * 的超时/异常渲染线程与业务解析线程重叠时，两个线程会同时进入解析，向三个 {@code LinkedMultiValueMap} 并发 add：表现为同一 name
 * 出现两次、{@code ConcurrentModificationException}， 或半初始化的 map（{@code parsed} 无同步发布）。
 * </p>
 * <p>
 * 修复：解析整段加 {@code synchronized (this)}（见 {@code parseIfNeeded} 的 Javadoc）。 本用例用两个线程从同一条起跑线
 * 同时触发两条路径，迭代多轮提高命中概率；修复前会偶发「条目翻倍 / CME」，修复后恒等。
 * </p>
 */
class NettyMultipartWebRequestConcurrencyTest {

    private static final int ROUNDS = 20;

    @Test
    void parseIfNeeded_concurrentEntryPoints_neverDuplicateOrCorrupt() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            NettyMultipartWebRequest multipart = buildTwoPartRequest();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                // 线程 A：对应 getParameterMap() 的锁路径（@RequestParam）
                pool.execute(() -> runOnce(start, done, failure, multipart::getParameters));
                // 线程 B：对应 getPartMap()/getMultiFileMap() 的无锁路径（@RequestPart / MultipartFile）
                pool.execute(() -> runOnce(start, done, failure, multipart::getParts));
                start.countDown();
                assertTrue(done.await(10, TimeUnit.SECONDS), "并发解析应在 10 秒内完成");
            } finally {
                pool.shutdownNow();
            }
            assertNull(failure.get(), "并发解析不得抛异常（CME 即失败）: " + failure.get());

            MultiValueMap<String, ?> parts = multipart.getParts();
            assertEquals(2, totalEntries(parts), "parts 条目数应恰为 part 数（并发双解析会翻倍）");
            for (String name : parts.keySet()) {
                assertEquals(1, parts.get(name).size(), "同一 name 不得被解析两次: " + name);
            }
            // 三个视图必须自洽：parts 覆盖 parameters + files
            assertEquals(totalEntries(parts),
                    totalEntries(multipart.getParameters()) + totalEntries(multipart.getFiles()),
                    "parameters + files 的条目数应等于 parts");

            multipart.release();
        }
    }

    private static void runOnce(CountDownLatch start, CountDownLatch done, AtomicReference<Throwable> failure,
            Runnable action) {
        try {
            start.await();
            action.run();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        } finally {
            done.countDown();
        }
    }

    private static int totalEntries(MultiValueMap<String, ?> map) {
        int total = 0;
        for (List<?> values : map.values()) {
            total += values.size();
        }
        return total;
    }

    /**
     * 两个 attribute part：每个都会被 add 进 parameters 与 parts，并发双解析会把条目数翻倍 （4 而不是 2），是判定解析是否互斥的最直接信号。
     */
    private static NettyMultipartWebRequest buildTwoPartRequest() {
        String boundary = "----Conc";
        String body = "--" + boundary + "\r\n" + "Content-Disposition: form-data; name=\"field1\"\r\n\r\n"
                + "value1\r\n" + "--" + boundary + "\r\n" + "Content-Disposition: form-data; name=\"field2\"\r\n\r\n"
                + "value2\r\n" + "--" + boundary + "--\r\n";
        ByteBuf content = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/upload", content);
        // 必须声明 multipart Content-Type：HttpPostRequestDecoder.isMultipart 靠它判定模式，
        // 缺失时整个体会被当成单个默认 attribute（这正是本用例最初只拿到 1 条 part 的原因）。
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "multipart/form-data; boundary=" + boundary);

        SupportMultipartResolver resolver = new SupportMultipartResolver();
        resolver.start(req);
        resolver.consume(req);
        return resolver.finish();
    }
}
