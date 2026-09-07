package io.springperf.webtest;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;

import java.time.Duration;

/**
 * 鍏变韩 Spring 涓婁笅鏂囷紙RANDOM_PORT锛夌殑 E2E 娴嬭瘯鍩虹被銆?
 * <p>浣跨敤 {@code RANDOM_PORT} + {@link LocalServerPort} 娉ㄥ叆瀹為檯绔彛锛?
 * 閬垮厤鍥哄畾绔彛锛圖EFINED_PORT锛夊湪绔彛琚崰鐢?骞惰鎵ц鏃剁殑鍋囧け璐ャ€?/p>
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class BaseE2ETest {

    /** Netty 瀹為檯缁戝畾绔彛锛圧ANDOM_PORT 涓嬬敱瀹瑰櫒娉ㄥ叆锛?*/
    @LocalServerPort
    protected int serverPort;

    public static final OkHttpClient CLIENT;

    static {
        HttpLoggingInterceptor logging =
                new HttpLoggingInterceptor(message -> {
                    System.out.println("[OKHTTP] " + message);
                });
        logging.setLevel(HttpLoggingInterceptor.Level.HEADERS);
        CLIENT = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(3))
                .readTimeout(Duration.ofSeconds(10))
                .writeTimeout(Duration.ofSeconds(10))
                .retryOnConnectionFailure(true)
                .addInterceptor(logging)
                .build();
        // 共享 CLIENT 跨测试类复用，不能在每个类 @AfterAll 关闭（会破坏后续类执行）；
        // 注册 JVM hook 仅在整个进程退出时清理连接池/调度线程，OkHttp 默认 daemon 线程不阻塞退出
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            CLIENT.dispatcher().executorService().shutdown();
            CLIENT.connectionPool().evictAll();
        }));
    }

    /** 鏋勯€犺闂矾寰勭殑瀹屾暣 URL锛堝惈娉ㄥ叆绔彛涓?context-path锛夈€?*/
    protected String url(String path) {
        return "http://localhost:" + serverPort + path;
    }

    /** 浠?context-path 涓哄墠缂€鏋勯€?URL锛坽@code /api/...}锛夈€?*/
    protected String urlApi(String path) {
        return url("/api" + path);
    }
}
