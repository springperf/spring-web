package io.springperf.benchmark.common;

import okhttp3.*;
import org.openjdk.jmh.annotations.*;

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

    public Request jsonEchoRequest;
    public Request helloGetRequest;
    public Request asyncGetRequest;
    public Request bytesGetRequest;
    public Request validatePostRequest;

    // P0 新增
    public Request jsonEchoLargeRequest;
    public Request largeResponseGetRequest;

    @Setup(Level.Trial)
    public void setup() {
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .build();

        String base = BenchmarkConstants.BASE_URL;

        jsonEchoRequest = new Request.Builder()
                .url(base + "/demo/echo")
                .post(RequestBody.create(BenchmarkConstants.JSON_MEDIA_TYPE,
                        BenchmarkConstants.ECHO_BODY))
                .build();

        helloGetRequest = new Request.Builder()
                .url(base + "/demo/hello/nameValue/aaaxxx"
                        + "?p1=v1&p2=v2&p3=v3&p4=v4&p5=v5")
                .get()
                .build();

        asyncGetRequest = new Request.Builder()
                .url(base + "/core/deferred-result")
                .get()
                .build();

        bytesGetRequest = new Request.Builder()
                .url(base + "/core/bytes")
                .get()
                .build();

        validatePostRequest = new Request.Builder()
                .url(base + "/core/validate")
                .post(RequestBody.create(BenchmarkConstants.JSON_MEDIA_TYPE,
                        BenchmarkConstants.VALIDATE_BODY))
                .build();

        // ========== P0 新场景 ==========

        jsonEchoLargeRequest = new Request.Builder()
                .url(base + "/demo/echo")
                .post(RequestBody.create(BenchmarkConstants.JSON_MEDIA_TYPE,
                        BenchmarkConstants.ECHO_BODY_LARGE))
                .build();

        largeResponseGetRequest = new Request.Builder()
                .url(base + BenchmarkConstants.LARGE_RESPONSE_PATH)
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
}
