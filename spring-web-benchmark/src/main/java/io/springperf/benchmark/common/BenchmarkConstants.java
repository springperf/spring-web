package io.springperf.benchmark.common;

import okhttp3.MediaType;

/**
 * JMH 基准测试全局常量。
 * 所有基准测试类共享相同的端口、路径和迭代配置，保证对比公平。
 */
public final class BenchmarkConstants {

    public static final int PORT = Integer.parseInt(
            System.getProperty("benchmark.port", "9090"));
    public static final String CONTEXT_PATH = "/api";
    public static final String BASE_URL = "http://localhost:" + PORT + CONTEXT_PATH;
    public static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");

    public static final int WARMUP_ITERATIONS = 5;
    public static final int WARMUP_TIME_SECONDS = 10;
    public static final int MEASUREMENT_ITERATIONS = 10;
    public static final int MEASUREMENT_TIME_SECONDS = 10;
    public static final int FORKS = 1;
    public static final int THREADS = 4;

    @SuppressWarnings("unused")
    public static final String JVM_ARGS = "-Xms1g -Xmx1g -XX:+UseG1GC";

    /** 基准报告输出目录 */
    public static final String OUTPUT_DIR = System.getProperty(
            "benchmark.output.dir", "target/benchmark-reports");

    /** 基准测试 profile 名称 */
    public static final String PROFILE_NAME = System.getProperty(
            "benchmark.profile.name", "unknown");

    /** 基准 JSON 请求体 */
    public static final String ECHO_BODY =
            "{\"message\":\"hello\",\"value\":42,\"nested\":{\"flag\":true}}";

    /** 基准校验请求体 */
    public static final String VALIDATE_BODY =
            "{\"name\":\"test\",\"age\":25}";

    /** 大请求体 JSON (~100KB)，测试 JSON 反序列化吞吐 */
    public static final String ECHO_BODY_LARGE;

    /** 大响应体路径 */
    public static final String LARGE_RESPONSE_PATH = "/core/large-response";

    // ==================== SSE 流式模拟 ====================

    /** SSE 块数 */
    public static final int SSE_CHUNK_COUNT = 100;
    /** 每块字符数 */
    public static final int SSE_CHUNK_SIZE = 200;
    /** 块间隔毫秒（0 = 无间隔，测试框架原始吞吐能力） */
    public static final int SSE_CHUNK_INTERVAL_MS = 0;
    /** SSE 流式接口路径 */
    public static final String SSE_PATH = "/core/sse/stream";

    static {
        StringBuilder sb = new StringBuilder(105 * 1024);
        sb.append("{\"data\":\"");
        for (int i = 0; i < 10240; i++) {
            sb.append("abcdefghij");
        }
        sb.append("\",\"count\":10240}");
        ECHO_BODY_LARGE = sb.toString();
    }

    private BenchmarkConstants() {
    }
}
