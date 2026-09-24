package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话持久化 E2E：{@code server.servlet.session.persistent=true} 时，重启前后同一 JSESSIONID 的会话属性可见（磁盘恢复），且 store-dir 中确有
 * {@code *.session} 文件。
 * <p>
 * 两次独立启动真实应用（非共享 Spring 上下文），分别绑定同一端口；重启后用 重启前拿到的 Cookie 访问 {@code /session/get} 验证属性恢复。内存存储在重启后必然 丢失，恢复成功即证明走的是
 * {@code FileHttpSessionStorage}。
 * </p>
 */
class SessionPersistenceE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10)).build();

    private static final Pattern JSESSIONID = Pattern.compile("JSESSIONID=([^;]+)");

    @TempDir
    Path storeDir;

    @Test
    void sessionSurvivesRestart() throws Exception {
        int port = freePort();
        String[] args = { "--server.port=" + port, "--server.servlet.session.persistent=true",
                "--server.servlet.session.store-dir=" + storeDir.toAbsolutePath() };
        // support-test 的 application.properties 配置了 context-path=/api
        String base = "http://localhost:" + port + "/api";

        // ---- 实例 1：写入会话属性 ----
        ConfigurableApplicationContext ctx1 = new SpringApplication(io.springperf.webtest.SupportTestApplication.class)
                .run(args);
        String sessionId;
        try {
            okhttp3.Request put = new okhttp3.Request.Builder()
                    .url(base + "/session/put?name=greeting&value=hello-e2e-persist").build();
            okhttp3.Response resp = CLIENT.newCall(put).execute();
            try {
                assertEquals(200, resp.code());
                String setCookie = resp.header("Set-Cookie");
                assertTrue(setCookie != null && setCookie.contains("JSESSIONID="),
                        "应下发会话 Cookie，实际 Set-Cookie=" + setCookie);
                sessionId = extractSessionId(setCookie);
            } finally {
                resp.close();
            }
        } finally {
            ctx1.close();
        }

        // ---- 重启落盘验证：store-dir 应有 *.session 文件 ----
        try (Stream<Path> files = Files.list(storeDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().endsWith(".session")), "持久化目录应存在 *.session 文件");
        }

        // ---- 实例 2：同一 store-dir，携带旧 Cookie，会话属性应恢复 ----
        ConfigurableApplicationContext ctx2 = new SpringApplication(io.springperf.webtest.SupportTestApplication.class)
                .run(args);
        try {
            okhttp3.Request get = new okhttp3.Request.Builder().url(base + "/session/get")
                    .header("Cookie", "JSESSIONID=" + sessionId).build();
            okhttp3.Response resp = CLIENT.newCall(get).execute();
            try {
                assertEquals(200, resp.code(), "重启后携带旧 JSESSIONID 应恢复会话");
                String body = resp.body().string();
                assertTrue(body.contains("hello-e2e-persist"), "重启后会话属性应从磁盘恢复，实际 body=" + body);
            } finally {
                resp.close();
            }
        } finally {
            ctx2.close();
        }
    }

    @Test
    void persistentExclude_attributeNotPersisted() throws Exception {
        int port = freePort();
        String[] args = { "--server.port=" + port, "--server.servlet.session.persistent=true",
                "--server.servlet.session.store-dir=" + storeDir.toAbsolutePath(),
                "--server.servlet.session.persistent-exclude=secret" };
        String base = "http://localhost:" + port + "/api";

        // ---- 实例 1：写入属性（greeting 为 /session/get 的 required 属性），
        // secret 在排除名单中，kept 正常落盘。三个 put 需携带同一会话 Cookie ----
        ConfigurableApplicationContext ctx1 = new SpringApplication(io.springperf.webtest.SupportTestApplication.class)
                .run(args);
        String sessionId;
        try {
            okhttp3.Response put0 = CLIENT.newCall(
                    new okhttp3.Request.Builder().url(base + "/session/put?name=greeting&value=hello-persist").build())
                    .execute();
            try {
                assertEquals(200, put0.code());
                String setCookie = put0.header("Set-Cookie");
                assertTrue(setCookie != null && setCookie.contains("JSESSIONID="));
                sessionId = extractSessionId(setCookie);
            } finally {
                put0.close();
            }
            okhttp3.Response put1 = CLIENT
                    .newCall(new okhttp3.Request.Builder().url(base + "/session/put?name=kept&value=kept-value")
                            .header("Cookie", "JSESSIONID=" + sessionId).build())
                    .execute();
            try {
                assertEquals(200, put1.code());
            } finally {
                put1.close();
            }
            okhttp3.Response put2 = CLIENT
                    .newCall(new okhttp3.Request.Builder().url(base + "/session/put?name=secret&value=secret-value")
                            .header("Cookie", "JSESSIONID=" + sessionId).build())
                    .execute();
            try {
                assertEquals(200, put2.code());
            } finally {
                put2.close();
            }
        } finally {
            ctx1.close();
        }

        // ---- 重启：kept 应恢复，secret 不应恢复（未落盘） ----
        ConfigurableApplicationContext ctx2 = new SpringApplication(io.springperf.webtest.SupportTestApplication.class)
                .run(args);
        try {
            okhttp3.Response resp = CLIENT.newCall(new okhttp3.Request.Builder().url(base + "/session/get")
                    .header("Cookie", "JSESSIONID=" + sessionId).build()).execute();
            try {
                assertEquals(200, resp.code());
                String body = resp.body().string();
                assertTrue(body.contains("hello-persist"), "排除名单外的属性应恢复（greeting 正常持久化），实际 body=" + body);
                assertTrue(!body.contains("secret-value"), "persistent-exclude 命中的属性不应落盘恢复，实际 body=" + body);
            } finally {
                resp.close();
            }
        } finally {
            ctx2.close();
        }
    }

    private static String extractSessionId(String setCookie) {
        Matcher m = JSESSIONID.matcher(setCookie);
        assertTrue(m.find(), "无法从 Set-Cookie 提取 JSESSIONID: " + setCookie);
        return m.group(1);
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
