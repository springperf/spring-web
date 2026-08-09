package io.springperf.benchmark.common;

import okhttp3.*;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.nio.charset.StandardCharsets;
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
 * <p>
 * 优化说明：
 * <ul>
 *   <li>请求体预编码为 byte[]，避免 OkHttp 运行时 {@code writeUtf8} 开销</li>
 *   <li>响应体用 {@code bytes()} 替代 {@code string()}，避免 String 分配 + UTF-8 解码</li>
 *   <li>SSE 流同用 {@code bytes()} 校验长度，避免 String 分配</li>
 * </ul>
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

    // 预编码请求体，避免每次请求 OkHttp 的 writeUtf8 开销
    private static final byte[] ECHO_BODY_BYTES =
            BenchmarkConstants.ECHO_BODY.getBytes(StandardCharsets.UTF_8);
    private static final byte[] VALIDATE_BODY_BYTES =
            BenchmarkConstants.VALIDATE_BODY.getBytes(StandardCharsets.UTF_8);

    /**
     * @param base 目标服务端 base URL，如 {@code http://localhost:9092/api}。
     *             由调用方构造：in-process 模式传 localhost，external 模式传远端 host（如 WSL IP）。
     */
    public void setup(String base) {
        client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .protocols(Collections.singletonList(Protocol.HTTP_1_1))
                .connectionPool(new ConnectionPool(128, 30, TimeUnit.SECONDS))
                .build();

        // byte[] 请求体避免 writeUtf8 开销
        jsonRequest = new Request.Builder()
                .url(base + "/demo/echo")
                .post(RequestBody.create(BenchmarkConstants.JSON_MEDIA_TYPE, ECHO_BODY_BYTES))
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
                .post(RequestBody.create(BenchmarkConstants.JSON_MEDIA_TYPE, VALIDATE_BODY_BYTES))
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
     * 执行 HTTP 请求并消费响应体。
     * 返回 byte[] 供 Blackhole.consume() 消费，防止 JIT 消除副作用。
     * 使用 {@code body.bytes()} 替代 {@code body.string()} 以消除 String 分配 + UTF-8 解码开销。
     */
    public byte[] executeAndConsume(Request request) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            byte[] body = response.body().bytes();
            if (!response.isSuccessful()) {
                throw new RuntimeException("Unexpected response: "
                        + response.code() + " " + body.length + "bytes");
            }
            return body;
        }
    }

    /**
     * 同步执行 SSE 流式请求，消费流式响应体。
     * 使用 {@code body.bytes()} 校验长度（≥10000 字节），替代 {@code body.string()} 避免 String 分配。
     * 返回总字节数供 Blackhole 消费。
     */
    public long executeAndConsumeStream(Request request) throws Exception {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new RuntimeException("Unexpected SSE response: " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new RuntimeException("SSE response body is null");
            }
            byte[] bodyBytes = body.bytes();
            int total = bodyBytes.length;
            if (total < 10000) {
                throw new RuntimeException("SSE response too short: " + total + " bytes (expected ~20700)");
            }
            return total;
        }
    }
}
