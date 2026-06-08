package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2 E2E 测试：高级组合场景。
 * 覆盖：占位符路径、@RequestMapping 负向条件、Filter 链顺序/阻断、@ExceptionHandler 父子异常路由。
 */
public class CoreFeaturesP2Test extends BaseE2ETest {

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType XML_TYPE = MediaType.parse("application/xml; charset=utf-8");
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private final String baseUrl = "http://localhost:9090/api";

    // ==================== 1. 占位符路径 ====================

    @Test
    void placeholderPath_resolvesFromEnvironment() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/p2/placeholder-test")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals(true, body.get("resolved"));
            assertEquals("placeholder", body.get("from"));
        }
    }

    @Test
    void placeholderSegment_resolvesInPath() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/p2-placeholder-seg/nested")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals(true, body.get("resolved"));
        }
    }

    // ==================== 2. 负向条件：consumes ====================

    @Test
    void notXmlConsumes_withJsonBody_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/p2/not-xml")
                .post(RequestBody.create("{\"key\":\"val\"}", JSON_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void notXmlConsumes_withXmlBody_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/p2/not-xml")
                .post(RequestBody.create("<root/>", XML_TYPE))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // consumes = "!application/xml" rejects XML content type → no route matched
            assertEquals(404, resp.code());
        }
    }

    // ==================== 3. 负向条件：headers ====================

    @Test
    void noBlockHeader_withoutHeader_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/p2/no-block-header")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void noBlockHeader_withBlockHeader_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/p2/no-block-header")
                .header("X-Block", "true")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // headers = "!X-Block" should NOT match when X-Block is present
            assertEquals(404, resp.code());
        }
    }

    // ==================== 4. 负向条件：params ====================

    @Test
    void noSkipParam_withoutSkip_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/p2/no-skip-param")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void noSkipParam_withSkip_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/p2/no-skip-param?skip=true")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // params = "!skip" should NOT match when skip param is present
            assertEquals(404, resp.code());
        }
    }

    // ==================== 5. @ExceptionHandler 父子异常路由 ====================

    @Test
    void parentException_caughtByParentHandler() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/p2/parent-exception")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(500, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("parent", body.get("handler"));
            assertTrue(((String) body.get("error")).contains("parent exception"));
        }
    }

    @Test
    void childException_caughtBySpecificHandler() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/p2/child-exception")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(400, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("child", body.get("handler"));
            assertTrue(((String) body.get("error")).contains("child exception"));
        }
    }

    // ==================== 6. Filter 阻断请求 ====================

    @Test
    void blockingFilter_returns403() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/p2/blocked")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(403, resp.code());
            assertEquals("blocked", resp.header("X-Blocking-Filter"));
            String body = resp.body().string();
            assertTrue(body.contains("blocked by filter"));
        }
    }

    // ==================== 7. Filter 链顺序 ====================

    @Test
    void filterOrder_bothHeadersPresent() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/p2/filter-order")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("executed", resp.header("X-Order-Low"));
            assertEquals("executed", resp.header("X-Order-High"));
        }
    }
}
