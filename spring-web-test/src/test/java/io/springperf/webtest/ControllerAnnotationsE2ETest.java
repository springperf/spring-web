package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: cover @Controller (non-REST) + method @ResponseBody, and Http2TestController
 * endpoints over HTTP/1.1 (they are reachable regardless of HTTP/2).
 */
public class ControllerAnnotationsE2ETest extends BaseE2ETest {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private String baseUrl() {
        return url("/api");
    }

    @Test
    void noRestController_echo_returnsResponseBody() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p0/no-rest-controller/echo?msg=hello-annot")
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertEquals("hello-annot", resp.body().string(),
                    "@Controller 方法级 @ResponseBody 应返回字符串");
        }
    }

    @Test
    void noRestController_save_returnsCreated() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/p0/no-rest-controller/save")
                .post(RequestBody.create("payload", JSON))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(201, resp.code(), "@Controller 方法级 @ResponseBody ResponseEntity 应透传状态码");
            assertEquals("saved:payload", resp.body().string());
        }
    }

    @Test
    void http2Controller_hello_overHttp11() throws Exception {
        Request req = new Request.Builder()
                .url(urlApi("/hello"))
                .get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = com.alibaba.fastjson2.JSON.parseObject(resp.body().string(), Map.class);
            assertEquals("hello", body.get("message"));
        }
    }

    @Test
    void http2Controller_sampleJsonBody_overHttp11() throws Exception {
        Request req = new Request.Builder()
                .url(urlApi("/sample-json-body"))
                .post(RequestBody.create("{\"a\":1}", JSON))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = com.alibaba.fastjson2.JSON.parseObject(resp.body().string(), Map.class);
            Map<?, ?> received = (Map<?, ?>) body.get("received");
            assertEquals(1, received.get("a"));
        }
    }
}