package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * E2E 验证类级别 @RequestMapping 约束（method/params/headers/consumes/produces）在路由匹配中生效。
 */
public class ClassLevelConstraintE2eTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api/class-level-constraint/echo";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    @Test
    void postJson_shouldReturn200() throws Exception {
        RequestBody body = RequestBody.create(JSON, "{}");
        Request req = new Request.Builder()
                .url(baseUrl)
                .post(body)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void getMethod_shouldReturn405() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl)
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(405, resp.code());
        }
    }
}