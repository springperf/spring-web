package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CoreFeaturesReturnValueAdvancedTest extends BaseE2ETest {

    private static final Logger log = LoggerFactory.getLogger(CoreFeaturesReturnValueAdvancedTest.class);

    private String baseUrl() {
        return url("/api/core");
    }

    @Test
    void fileDownload_returnsFileContent() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/file-download").get().build();
        // FileReturnValueResolver → NettyServerHttpResponse.writeFile 设置 Content-Length + LastHttpContent，
        // OkHttp 可完整读取，不应超时。超时即视为实现缺陷。
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertNotNull(body);
            assertFalse(body.isEmpty(), "File download should return content");
        }
    }

    @Test
    void completionStage_returnsResult() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/completion-stage").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            log.info("completion-stage response: {}", body);
            assertTrue(body.contains("completion-stage-result"), "CompletionStage should return result, got: " + body);
        }
    }

    @Test
    void webAsyncTask_returnsResult() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/async-task").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("async-task-result"), "WebAsyncTask should return result, got: " + body);
        }
    }

    @Test
    void asyncTimeout_triggersTimeoutHandling() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/async-timeout").get().build();
        // WebAsyncTask(timeout=100ms) 的任务 sleep 500ms 必然超时。两种合法收尾（竞态）：
        // - 框架超时先到 → 503（AsyncRequestTimeoutException，Spring 语义）；
        // - 任务被中断后由应用异常处理兜底 → 500（本示例应用的 advice）。
        // 原先硬编码 500 属竞态型 flaky（跨模块全量运行时暴露）。此处接受两种结果，
        // 但超时本身不得被豁免（既不能 200、也不能悬挂）。
        try (Response resp = CLIENT.newCall(req).execute()) {
            int code = resp.code();
            assertTrue(code == 503 || code == 500, "异步任务超时应以 503（框架超时）或 500（应用异常兜底）收尾，实际 " + code);
        }
    }

    @Test
    void dateFormat_serializesCorrectly() throws Exception {
        Request req = new Request.Builder().url(baseUrl() + "/date-format").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            Map<String, Object> result = JSON.parseObject(body, Map.class);
            assertEquals("date-test", result.get("message"));
            // Spring Boot 默认 WRITE_DATES_AS_TIMESTAMPS=false：Date 序列化为 ISO-8601 字符串
            Object date = result.get("date");
            assertTrue(date instanceof String, "Date 应以 ISO 字符串被序列化，实际: " + date);
            assertFalse(((String) date).isEmpty());
        }
    }
}
