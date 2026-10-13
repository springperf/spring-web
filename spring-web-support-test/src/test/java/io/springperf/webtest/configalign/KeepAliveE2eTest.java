package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * keep-alive 调优 E2E（真实管线装配验证）： {@code server.max-keep-alive-requests=2} 时，同一连接第 2 个响应应携带
 * {@code Connection: close}，之后新连接继续正常服务。
 * <p>
 * 此测试可捕获「KeepAliveHandler 装配在 httpHandler 之后收不到任何事件」类的 管线位置回归——该缺陷曾使 keep-alive 计数在真实服务中完全失效而单测全绿。
 * </p>
 */
@SpringBootTest(classes = io.springperf.webtest.SupportTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.max-keep-alive-requests=2")
class KeepAliveE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    @LocalServerPort
    int port;

    private String url(String path) {
        // support-test 的 application.properties 配置了 context-path=/api
        return "http://localhost:" + port + "/api" + path;
    }

    private okhttp3.Response get(String path) throws Exception {
        okhttp3.Request req = new okhttp3.Request.Builder().url(url(path)).build();
        return CLIENT.newCall(req).execute();
    }

    @Test
    void maxKeepAliveRequests_connectionClosedAtLimit_thenServerStillServes() throws Exception {
        // 请求 1：计数 1 < 2，不关闭
        okhttp3.Response r1 = get("/session/get-optional");
        try {
            assertEquals(200, r1.code());
            assertFalse("close".equalsIgnoreCase(r1.header("Connection", "")), "第 1 个响应不应携带 Connection: close");
        } finally {
            r1.close();
        }

        // 请求 2：计数达上限，本响应结束即关闭（响应头带 Connection: close）
        okhttp3.Response r2 = get("/session/get-optional");
        String conn2;
        try {
            assertEquals(200, r2.code());
            conn2 = r2.header("Connection");
            assertNotNull(conn2, "第 2 个响应应显式携带 Connection 头");
            assertEquals("close", conn2.toLowerCase(), "达到 max-keep-alive-requests 的响应应携带 Connection: close");
        } finally {
            r2.close();
        }

        // 请求 3：OkHttp 新建连接，服务端在新连接上继续正常服务
        okhttp3.Response r3 = get("/session/get-optional");
        try {
            assertEquals(200, r3.code(), "连接关闭后应能继续建立新连接服务");
        } finally {
            r3.close();
        }
    }
}
