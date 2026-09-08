package io.springperf.webtest;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

/**
 * 共享 Spring 上下文（RANDOM_PORT）的 E2E 测试基类。
 * <p>使用 {@code RANDOM_PORT} + {@link LocalServerPort} 注入实际端口，
 * 避免固定端口（DEFINED_PORT）在端口被占用/并行执行时的假失败。</p>
 */
@SpringBootTest(classes = SupportTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseE2ETest {

    /** Netty 实际绑定端口（RANDOM_PORT 下由容器注入） */
    @LocalServerPort
    protected int serverPort;

    public static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .retryOnConnectionFailure(true)
            .build();

    static {
        // 共享 CLIENT 跨测试类复用，不能在每个类 @AfterAll 关闭（会破坏后续类执行）；
        // 注册 JVM hook 仅在整个进程退出时清理连接池/调度线程，OkHttp 默认 daemon 线程不阻塞退出
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CLIENT.dispatcher().executorService().shutdown();
            CLIENT.connectionPool().evictAll();
        }));
    }

    /** 构造访问路径的完整 URL（含注入端口与 context-path）。 */
    protected String url(String path) {
        return "http://localhost:" + serverPort + path;
    }
}