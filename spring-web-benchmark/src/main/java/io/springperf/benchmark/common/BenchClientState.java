package io.springperf.benchmark.common;

import okhttp3.*;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * JMH @State(Scope.Thread) — 每个 JMH 工作线程拥有独立的 HTTP 客户端和预构建请求。
 * <p>
 * 注意：此类声明 {@code @State(Scope.Thread)} 但被 {@code @State(Scope.Benchmark)} 的
 * Benchmark 类持有。JMH 在此嵌套场景下以内部类（Thread）作用域为准——每个 JMH 工作线程
 * 得到独立的 BenchClientState 实例，避免 OkHttp 连接池的线程竞争。
 * <p>
 * 在 @Setup(Level.Trial) 中预构建 Request 对象，避免基准测试期间的序列化开销。
 * 强制使用 HTTP/1.1 保证公平对比（自定义框架仅支持 HTTP/1.1）。
 */
@State(Scope.Thread)
public class BenchClientState {

    public OkHttpClient client;

    public Request jsonRequest;
    public Request getRequest;
    public Request asyncRequest;
    public Request bytesRequest;
    public Request validRequest;

    public Request bytesLargeRequest;
    public Request sseRequest;

    /**
     * @param actualPort 服务器实际绑定的端口（可能因 fallback 不同于配置端口）
     */
    public void setup(int actualPort) {
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .build();

        String base = "http://localhost:" + actualPort + BenchmarkConstants.CONTEXT_PATH;

        jsonRequest = new Request.Builder()
                .url(base + "/demo/echo")
                .post(RequestBody.create(BenchmarkConstants.JSON_MEDIA_TYPE,
                        BenchmarkConstants.ECHO_BODY))
                .build();

        getRequest = new Request.Builder()
                .url(base + "/demo/hello/nameValue/aaaxxx"
                        + "?p1=1&p2=v2&p3=v3&p4=v4&p5=v5")
                .get()
                .build();

        asyncRequest = new Request.Builder()
                .url(base + "/core/deferred-result")
                .get()
                .build();

        bytesRequest = new Request.Builder()
                .url(base + "/core/bytes")
                .get()
                .build();

        validRequest = new Request.Builder()
                .url(base + "/core/validate")
                .post(RequestBody.create(BenchmarkConstants.JSON_MEDIA_TYPE,
                        BenchmarkConstants.VALIDATE_BODY))
                .build();

        bytesLargeRequest = new Request.Builder()
                .url(base + BenchmarkConstants.LARGE_RESPONSE_PATH)
                .get()
                .build();

        sseRequest = new Request.Builder()
                .url(base + BenchmarkConstants.SSE_PATH)
                .get()
                .build();

        }

    @TearDown(Level.Trial)
    public void cleanup() {
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    /**
     * 执行 HTTP 请求并完全消费响应体。
     * 返回 body 字符串供 Blackhole.consume() 消费，防止 JIT 消除副作用。
     */
    public String executeAndConsume(Request request) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            String body = response.body().string();
            if (!response.isSuccessful()) {
                throw new RuntimeException("Unexpected response: "
                        + response.code() + " " + body);
            }
            return body;
        }
    }

    /**
     * 同步执行 SSE 流式请求，消费流式响应体。
     * 以 8KB 块读取 InputStream 并丢弃，模拟真实 SSE 客户端消费行为。
     * 使用同步 execute() 避免 async 模式下的线程管理和超时竞态。
     */
    public String executeAndConsumeStream(Request request) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Unexpected SSE response: " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new RuntimeException("SSE response body is null");
            }
            String bodyStr = body.string();
            int total = bodyStr.length();
            if (total < 10000) {
                throw new RuntimeException("SSE response too short: " + total + " bytes (expected ~20700)");
            }
            return "SSE:" + total + "bytes";
        }
    }
}
