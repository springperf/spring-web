package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E2E 验证类级别 @RequestMapping 的 <b>method</b> 约束在路由匹配中生效。
 * <p>
 * 类级 params/headers/consumes/produces 约束目前没有对应的测试端点，故未覆盖（原注释声称覆盖五类，与实现不符）。
 * </p>
 */
public class ClassLevelConstraintE2eTest extends BaseE2ETest {

    private String baseUrl() {
        return url("/api/class-level-constraint/echo");
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Test
    void postJson_shouldReturn200() throws Exception {
        RequestBody body = RequestBody.create(JSON, "{}");
        Request req = new Request.Builder().url(baseUrl()).post(body).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String respBody = resp.body().string();
            assertEquals("ok", com.alibaba.fastjson2.JSON.parseObject(respBody).getString("status"),
                    "类级约束端点应返回 {\"status\":\"ok\"}，实际: " + respBody);
        }
    }

    @Test
    void getMethod_shouldReturn405() throws Exception {
        Request req = new Request.Builder().url(baseUrl()).get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(405, resp.code(), "类级 method=POST 约束下 GET 应 405");
            assertEquals("POST", resp.header("Allow"), "405 的 Allow 应反映类级约束允许的方法，实际响应头: " + resp.headers());
        }
    }
}
