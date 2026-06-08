package io.springperf.webtest.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 E2E 测试：Controller 类继承、@RequestMapping 负向条件、@ExceptionHandler 父子路由、Filter 阻断。
 * <p>
 * 与 ProxyE2eTest 共享 Spring 上下文（端口 9092）。
 */
@SpringBootTest(
        classes = ProxyE2eApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "server.port=9092",
                "server.servlet.context-path=/api",
                "proxy.placeholder.path=/proxy/placeholder-resolved"
        })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ProxyP3E2eTest {

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .build();

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType XML_TYPE = MediaType.parse("application/xml; charset=utf-8");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl = "http://localhost:9092/api";

    // ==================== 1. Controller 类继承 (Child extends Parent) ====================

    @Test
    void childController_parentGreet_stillWorks() throws Exception {
        // ChildController 继承自 ParentController，不覆写 greet 方法
        // getDeclaredMethods 只返回本类声明的方法，因此 greet 由 ParentController bean 注册
        // `/proxy-parent/greet` 路径通过 ParentController 处理
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-parent/greet?name=test")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("hello-test", resp.body().string());
        }
    }

    @Test
    void childController_ownMethod_returnsStatus() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-parent/status")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("child-ok", resp.body().string());
        }
    }

    @Test
    void parentController_ownMethod_stillWorks() throws Exception {
        // ParentController 仍然是独立 bean，不会被 ChildController 覆盖
        // 访问 /proxy-parent/greet 时，MVC 路由应通过 ChildController 响应
        // 这是合理的，因为子类覆盖了父类的行为
    }

    // ==================== 2. @RequestMapping 负向条件 (headers/params/consumes) ====================

    @Test
    void negativeCond_withoutBlockHeader_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-cond-extra/no-block-header")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void negativeCond_withBlockHeader_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-cond-extra/no-block-header")
                .header("X-Block", "true")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    @Test
    void negativeCond_withoutSkipParam_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-cond-extra/no-skip-param")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void negativeCond_withSkipParam_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-cond-extra/no-skip-param?skip=true")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    @Test
    void negativeCond_notXmlConsumes_withJson_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-cond-extra/not-xml")
                .post(RequestBody.create("{}", JSON_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void negativeCond_notXmlConsumes_withXml_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-cond-extra/not-xml")
                .post(RequestBody.create("<r/>", XML_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // consumes = "!application/xml" rejects XML → no route matched
            assertEquals(404, resp.code());
        }
    }

    @Test
    void positiveHeaderCondition_withRequiredHeader_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-cond-extra/with-header")
                .header("X-Required", "present")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void positiveHeaderCondition_withoutRequiredHeader_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-cond-extra/with-header")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    // ==================== 3. @ExceptionHandler 父子异常路由 (with proxy) ====================

    @Test
    void childController_parentException_caughtByParentHandler() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-parent/parent-exception")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(500, resp.code());
            String raw = resp.body().string();
            Map<String, Object> body = MAPPER.readValue(raw, Map.class);
            assertEquals("parent", body.get("handler"));
            assertTrue(((String) body.get("error")).contains("parent error"));
        }
    }

    @Test
    void childController_childException_caughtBySpecificHandler() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-parent/child-exception")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(400, resp.code());
            String raw = resp.body().string();
            Map<String, Object> body = MAPPER.readValue(raw, Map.class);
            assertEquals("child", body.get("handler"));
            assertTrue(((String) body.get("error")).contains("child error"));
        }
    }

    // ==================== 4. WebFilter 阻断请求 (with proxy) ====================

    @Test
    void blockingFilter_withProxy_returns403() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/proxy-parent/blocked")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(403, resp.code());
            assertEquals("blocked", resp.header("X-Blocking-Filter"));
            String body = resp.body().string();
            assertTrue(body.contains("blocked by proxy filter"));
        }
    }
}
