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
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * resetBuffer/reset 契约 E2E（Servlet 规范 §5.6）：
 * <ul>
 *   <li>未提交：清空已缓冲内容（含 Writer 未 flush 的编码缓冲）；</li>
 *   <li>已提交：抛 IllegalStateException（已发出的内容无法收回）。</li>
 * </ul>
 */
@SpringBootTest(classes = {ConfigAlignTestApp.class, ResetBufferCommittedE2eTest.Cfg.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.servlet.context-path=/")
class ResetBufferCommittedE2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .build();

    @LocalServerPort
    int port;

    private String body(String path) throws IOException {
        Response resp = CLIENT.newCall(new Request.Builder()
                .url("http://localhost:" + port + path).build()).execute();
        try {
            return resp.code() + ":" + resp.body().string();
        } finally {
            resp.close();
        }
    }

    @Test
    void uncommittedResetBuffer_discardsWriterContent() throws Exception {
        assertEquals("200:final-content", body("/e2e-rb/uncommitted"),
                "未提交时 resetBuffer 应丢弃已写内容（含未 flush 的 Writer 缓冲）");
    }

    @Test
    void committedResetBuffer_throwsAndStreamIntact() throws Exception {
        assertEquals("200:rb-part-1\nreset-rejected", body("/e2e-rb/committed"),
                "已提交后 resetBuffer 应抛 IllegalStateException，且已发出的流不受影响");
    }

    @Test
    void uncommittedReset_discardsContentAndHeaders() throws Exception {
        assertEquals("200:after-reset", body("/e2e-rb/reset"),
                "未提交时 reset 应丢弃内容（并重置状态/头）");
    }

    @TestConfiguration
    static class Cfg {
        @Bean
        RbController rbController() {
            return new RbController();
        }
    }

    @RestController
    static class RbController {

        @GetMapping("/e2e-rb/uncommitted")
        public void uncommitted(HttpServletResponse response) throws IOException {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("will-be-discarded");
            response.resetBuffer();
            response.getWriter().write("final-content");
        }

        @GetMapping("/e2e-rb/committed")
        public void committed(HttpServletResponse response) throws Exception {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("rb-part-1\n");
            response.getWriter().flush();
            try {
                response.resetBuffer();
                response.getWriter().write("SHOULD-NOT-APPEAR");
            } catch (IllegalStateException expected) {
                response.getWriter().write("reset-rejected");
            }
            response.getWriter().flush();
        }

        @GetMapping("/e2e-rb/reset")
        public void fullReset(HttpServletResponse response) throws IOException {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("discarded-too");
            response.reset();
            response.getWriter().write("after-reset");
        }
    }
}
