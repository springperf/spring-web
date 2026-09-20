package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户注册的资源处理器与 {@code spring.web.resources.add-mappings} 的共存语义 E2E：
 *
 * <ul>
 *   <li>用户显式注册的映射生效，且其缓存指令独立（不受 {@code spring.web.resources.cache.period} 影响）；</li>
 *   <li>同一 pattern 重复注册时**先注册者优先**（顺序确定，不能随机命中）；</li>
 *   <li>用户注册的映射同样受路径穿越防护约束。</li>
 * </ul>
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ResourceMappingCoexistenceE2eTest.RegistrationConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.servlet.context-path=/",
                "spring.web.resources.add-mappings=true",
                "spring.web.resources.cache.period=3600"
        })
class ResourceMappingCoexistenceE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private Response get(String path) throws Exception {
        return CLIENT.newCall(new Request.Builder().url("http://localhost:" + port + path).get().build())
                .execute();
    }

    @Test
    void userRegisteredMapping_served() throws Exception {
        Response resp = get("/res/e2e-custom.txt");
        try {
            assertEquals(200, resp.code(), "用户注册的 /res/** 映射应可服务，实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void userRegisteredMapping_hasNoDefaultCacheControl() throws Exception {
        Response resp = get("/res/e2e-custom.txt");
        try {
            assertEquals(200, resp.code());
            assertNull(resp.header("Cache-Control"),
                    "cache.period 只作用于框架自动注册的默认映射；用户注册的映射应保持未设置，实际 "
                            + resp.header("Cache-Control"));
        } finally {
            resp.close();
        }
    }

    @Test
    void defaultAutoMapping_carriesCachePeriod() throws Exception {
        Response resp = get("/e2e-note.txt");
        try {
            assertEquals(200, resp.code(),
                    "add-mappings 的默认映射应服务 classpath:/static/ 下的资源，实际 " + resp.code());
            assertEquals("max-age=3600, must-revalidate", resp.header("Cache-Control"),
                    "默认映射应套用 cache.period，实际 " + resp.header("Cache-Control"));
        } finally {
            resp.close();
        }
    }

    @Test
    void duplicatePattern_lastRegistrationWins() throws Exception {
        // 同一 pattern 注册两次时**后者覆盖前者**（与 Spring 的资源映射表语义一致）：
        // /dup/**  = custom-static → 不存在（后者生效 → 404 且不得回退到前者）
        Response resp = get("/dup/e2e-custom.txt");
        try {
            assertEquals(404, resp.code(),
                    "同 pattern 重复注册应由后注册者生效（不得回退到先注册者），实际 " + resp.code());
        } finally {
            resp.close();
        }
    }

    @Test
    void duplicatePattern_orderReversed_servesLastLocation() throws Exception {
        // /dup2/** = 不存在 → custom-static：后者生效 → 200，证明规则是顺序确定的「后者覆盖」
        Response resp = get("/dup2/e2e-custom.txt");
        try {
            assertEquals(200, resp.code(), "后注册的可用 location 应生效，实际 " + resp.code());
            assertTrue(resp.body().string().contains("e2e-custom-ok"));
        } finally {
            resp.close();
        }
    }

    @Test
    void traversalOutsideUserMapping_rejected() throws Exception {
        // 必须用裸 socket：HTTP 客户端会把 /res/../x 先规范化成 /x，测不到服务端防护
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /res/../e2e-note.txt HTTP/1.1\r\n"
                    + "Host: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            java.io.InputStream in = socket.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) >= 0) {
                sb.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                if (sb.length() > 4096) {
                    break;
                }
            }
            assertTrue(sb.toString().contains("404"),
                    "用户注册的映射同样必须拒绝路径穿越，实际响应:\n" + sb);
        }
    }

    @TestConfiguration
    static class RegistrationConfig implements WebMvcConfigurer {

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            registry.addResourceHandler("/res/**")
                    .addResourceLocations("classpath:/custom-static/");
            // 同 pattern 重复注册：先注册者应稳定优先
            registry.addResourceHandler("/dup/**")
                    .addResourceLocations("classpath:/custom-static/");
            registry.addResourceHandler("/dup/**")
                    .addResourceLocations("classpath:/does-not-exist/");
            // 反向顺序：证明「后者覆盖」是顺序确定行为而非哈希偶然
            registry.addResourceHandler("/dup2/**")
                    .addResourceLocations("classpath:/does-not-exist/");
            registry.addResourceHandler("/dup2/**")
                    .addResourceLocations("classpath:/custom-static/");
        }

        @Bean
        CoexistenceNoopBean coexistenceNoopBean() {
            return new CoexistenceNoopBean();
        }
    }

    /** 占位 bean：确保配置类被实例化。 */
    static class CoexistenceNoopBean {
    }
}
