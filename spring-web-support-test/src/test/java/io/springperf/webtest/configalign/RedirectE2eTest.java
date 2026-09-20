package io.springperf.webtest.configalign;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code HttpServletResponse.sendRedirect} E2E（context-path=/，无代理）：
 * 状态码 302、Location 绝对化、外部 URL 原样透传、相对路径按 Servlet 规范解析为绝对 URL、
 * 已提交后重定向抛错、以及与会话 URL 重写的组合。
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, RedirectE2eTest.RedirectConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.context-path=/")
class RedirectE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .followRedirects(false)
            .build();

    @LocalServerPort
    int port;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private Response get(String path, String... headers) throws Exception {
        Request.Builder b = new Request.Builder().url(url(path)).get();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return CLIENT.newCall(b.build()).execute();
    }

    @Test
    void sendRedirect_absolutePath_returns302WithAbsoluteLocation() throws Exception {
        Response resp = get("/e2e-redir/to-absolute");
        try {
            assertEquals(302, resp.code(), "sendRedirect 应返回 302 (SC_FOUND)");
            assertEquals("http://localhost:" + port + "/e2e-redir/target",
                    resp.header("Location"),
                    "以 / 开头的路径应补全为基于当前请求的绝对 URL");
            assertEquals("", resp.body().string(), "重定向响应不应带 body");
        } finally {
            resp.close();
        }
    }

    @Test
    void sendRedirect_externalUrl_passedThroughUnchanged() throws Exception {
        Response resp = get("/e2e-redir/to-external");
        try {
            assertEquals(302, resp.code());
            assertEquals("https://external.example/path?q=1", resp.header("Location"),
                    "外部绝对 URL 不得被改写成当前主机");
        } finally {
            resp.close();
        }
    }

    @Test
    void sendRedirect_relativePath_resolvedAgainstRequestUri() throws Exception {
        // Servlet 规范：相对路径必须由容器转换为绝对 URL（按当前请求 URI 所在目录解析）
        Response resp = get("/e2e-redir/to-relative");
        try {
            assertEquals(302, resp.code());
            assertEquals("http://localhost:" + port + "/e2e-redir/next",
                    resp.header("Location"),
                    "相对路径应解析为当前请求目录下的绝对 URL");
        } finally {
            resp.close();
        }
    }

    @Test
    void sendRedirect_relativeParentDir_resolvedAgainstRequestDirectory() throws Exception {
        // /e2e-redir/nested/to-parent 的目录为 /e2e-redir/nested/，../sibling → /e2e-redir/sibling
        Response resp = get("/e2e-redir/nested/to-parent");
        try {
            assertEquals(302, resp.code());
            assertEquals("http://localhost:" + port + "/e2e-redir/sibling",
                    resp.header("Location"), ".. 应回退一层目录，实际 " + resp.header("Location"));
        } finally {
            resp.close();
        }
    }

    @Test
    void sendRedirect_relativeWithQuery_keepsQueryString() throws Exception {
        Response resp = get("/e2e-redir/to-relative-with-query");
        try {
            assertEquals(302, resp.code());
            assertEquals("http://localhost:" + port + "/e2e-redir/next?a=1&b=2",
                    resp.header("Location"), "相对路径的 query 应保留，实际 " + resp.header("Location"));
        } finally {
            resp.close();
        }
    }

    @Test
    void sendRedirect_protocolRelativeAuthority_passedThrough() throws Exception {
        Response resp = get("/e2e-redir/to-protocol-relative");
        try {
            assertEquals(302, resp.code());
            assertEquals("//other.example/path", resp.header("Location"),
                    "网络路径引用（//host）应视为绝对地址，不得再拼当前权威");
        } finally {
            resp.close();
        }
    }

    @Test
    void sendRedirect_head_returns302WithoutBody() throws Exception {
        Response resp = CLIENT.newCall(new Request.Builder()
                .url(url("/e2e-redir/to-absolute")).head().build()).execute();
        try {
            assertEquals(302, resp.code());
            assertEquals("http://localhost:" + port + "/e2e-redir/target", resp.header("Location"));
            assertEquals("", resp.body().string(), "HEAD 重定向不应有 body");
        } finally {
            resp.close();
        }
    }

    @Test
    void sendRedirect_queryStringPreservedInTarget() throws Exception {
        Response resp = get("/e2e-redir/to-absolute-with-query");
        try {
            assertEquals(302, resp.code());
            assertEquals("http://localhost:" + port + "/e2e-redir/target?a=1&b=2",
                    resp.header("Location"), "目标路径中的 query 应保留");
        } finally {
            resp.close();
        }
    }

    @Test
    void sendRedirect_afterCommit_keepsCommittedResponse() throws Exception {
        // 已提交（flushBuffer 真正写出响应）后 sendRedirect 抛 IllegalStateException；
        // 此时响应已上线，客户端看到的就是已提交的 200 响应，重定向不得生效
        // （与 Tomcat 一致：错误页也无法再渲染，只能保持已提交内容）。
        Response resp = get("/e2e-redir/after-commit");
        try {
            assertEquals(200, resp.code(),
                    "已提交响应无法被改写，客户端应收到原 200，实际 " + resp.code());
            assertTrue(resp.body().string().contains("already-sent"),
                    "已提交内容应原样送达");
            assertTrue(resp.header("Location") == null,
                    "重定向失败后不得残留 Location 头，实际 " + resp.header("Location"));
        } finally {
            resp.close();
        }
    }

    @Test
    void sendRedirect_writerFlushCommits_thenRedirectFails() throws Exception {
        // 对齐 Tomcat（Servlet 规范）：getWriter().flush() 提交响应并送出内容，
        // 因此其后的 sendRedirect 必然失败（IllegalStateException），客户端只看到已提交的 200。
        Response resp = get("/e2e-redir/writer-flush-then-redirect");
        try {
            assertEquals(200, resp.code(),
                    "writer.flush() 已提交响应后 sendRedirect 不得生效，实际 " + resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("already-sent"), "已 flush 的内容应送达，实际 body=" + body);
            assertTrue(resp.header("Location") == null,
                    "重定向失败后不得残留 Location 头，实际 " + resp.header("Location"));
        } finally {
            resp.close();
        }
    }

    @Test
    void sendRedirect_nullLocation_throws() throws Exception {
        Response resp = get("/e2e-redir/null-location");
        try {
            assertEquals(500, resp.code(), "location 为 null 应抛 IllegalArgumentException → 500");
        } finally {
            resp.close();
        }
    }

    @Test
    void sendRedirect_locationIsVisibleToClientAsHeaderOnly() throws Exception {
        Response resp = get("/e2e-redir/to-absolute");
        try {
            // 302 + Location 是唯一契约：不得同时写出 200 语义的 body
            assertEquals(302, resp.code());
            assertNotNull(resp.header("Location"));
            assertEquals(0, resp.body().contentLength(), "重定向 body 长度应为 0");
        } finally {
            resp.close();
        }
    }

    @TestConfiguration
    static class RedirectConfig {
        @Bean
        RedirectController redirectController() {
            return new RedirectController();
        }
    }

    @RestController
    static class RedirectController {

        @GetMapping("/e2e-redir/target")
        public String target() {
            return "target-body";
        }

        @GetMapping("/e2e-redir/to-absolute")
        public String toAbsolute(HttpServletResponse response) throws IOException {
            response.sendRedirect("/e2e-redir/target");
            return null;
        }

        @GetMapping("/e2e-redir/to-absolute-with-query")
        public String toAbsoluteWithQuery(HttpServletResponse response) throws IOException {
            response.sendRedirect("/e2e-redir/target?a=1&b=2");
            return null;
        }

        @GetMapping("/e2e-redir/to-external")
        public String toExternal(HttpServletResponse response) throws IOException {
            response.sendRedirect("https://external.example/path?q=1");
            return null;
        }

        @GetMapping("/e2e-redir/to-relative")
        public String toRelative(HttpServletResponse response) throws IOException {
            response.sendRedirect("next");
            return null;
        }

        @GetMapping("/e2e-redir/to-relative-with-query")
        public String toRelativeWithQuery(HttpServletResponse response) throws IOException {
            response.sendRedirect("next?a=1&b=2");
            return null;
        }

        @GetMapping("/e2e-redir/nested/to-parent")
        public String toParent(HttpServletResponse response) throws IOException {
            response.sendRedirect("../sibling");
            return null;
        }

        @GetMapping("/e2e-redir/to-protocol-relative")
        public String toProtocolRelative(HttpServletResponse response) throws IOException {
            response.sendRedirect("//other.example/path");
            return null;
        }

        @GetMapping("/e2e-redir/after-commit")
        public String afterCommit(HttpServletResponse response) throws IOException {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("already-sent");
            response.flushBuffer();
            response.sendRedirect("/e2e-redir/target");
            return null;
        }

        @GetMapping("/e2e-redir/writer-flush-then-redirect")
        public String writerFlushThenRedirect(HttpServletResponse response) throws IOException {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("already-sent");
            response.getWriter().flush();
            response.sendRedirect("/e2e-redir/target");
            return null;
        }

        @GetMapping("/e2e-redir/null-location")
        public String nullLocation(HttpServletResponse response) throws IOException {
            response.sendRedirect((String) null);
            return null;
        }
    }
}
