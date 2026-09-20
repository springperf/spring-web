package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CoreFeaturesContentNegotiationTest extends BaseE2ETest {

    private String baseUrl() {
        return url("/api/core");
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final MediaType XML = MediaType.parse("application/xml; charset=utf-8");
    private static final MediaType TEXT = MediaType.parse("text/plain; charset=utf-8");

    @Test
    void consumesJson_withJsonContentType_returns200() throws Exception {
        RequestBody body = RequestBody.create(JSON, "{\"key\":\"value\"}");
        Request req = new Request.Builder()
                .url(baseUrl() + "/consumes-json")
                .post(body)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String result = resp.body().string();
            assertEquals("json:{\"key\":\"value\"}", result);
        }
    }

    @Test
    void consumesJson_withXmlContentType_returns415() throws Exception {
        RequestBody body = RequestBody.create(XML, "<root/>");
        Request req = new Request.Builder()
                .url(baseUrl() + "/consumes-json")
                .post(body)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // 对齐 Spring MVC：路径匹配但 consumes 不满足 → 415 Unsupported Media Type
            assertEquals(415, resp.code());
        }
    }

    @Test
    void producesJson_withJsonAccept_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/produces-json")
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void producesXml_withNonMatchingAccept_returns406() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/produces-xml")
                .header("Accept", "text/plain")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            // 对齐 Spring MVC：路径匹配但 produces 不被 Accept 接受 → 406 Not Acceptable
            assertEquals(406, resp.code());
        }
    }

    @Test
    void headersCondition_withCorrectHeader_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/headers-custom")
                .header("X-Custom", "myvalue")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("header-matched", body);
        }
    }

    @Test
    void headersCondition_withWrongHeader_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/headers-custom")
                .header("X-Custom", "wrongvalue")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    @Test
    void headersCondition_withoutHeader_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/headers-custom")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    @Test
    void paramsMismatch_withoutRequiredParam_returns404() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/params-mismatch")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    @Test
    void paramsMismatch_withCorrectParam_returns200() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl() + "/params-mismatch?x=y")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("params-matched", body);
        }
    }
}