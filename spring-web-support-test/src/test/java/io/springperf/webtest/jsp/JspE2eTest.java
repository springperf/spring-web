package io.springperf.webtest.jsp;

import io.springperf.webtest.BaseE2ETest;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JspE2eTest extends BaseE2ETest {

    private static final String BASE = "http://localhost:9090/api";

    @Test
    void jspRoute_rendersJsp() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/jsp/hello.jsp")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            String body = resp.body().string();
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + body);
            assertTrue(body.contains("hello jsp"), "body=" + body);
            assertTrue(body.contains("sum= 2"), "body=" + body);
        }
    }

    @Test
    void controllerView_rendersJspWithModel() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/jsp-view")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            String body = resp.body().string();
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + body);
            assertTrue(body.contains("hello jsp"), "body=" + body);
            assertTrue(body.contains("name= spring-perf"), "body=" + body);
        }
    }

    @Test
    void controllerView_suffixForm_rendersJsp() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/jsp-view-suffix")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            String body = resp.body().string();
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + body);
            assertTrue(body.contains("hello jsp"), "body=" + body);
        }
    }

    @Test
    void controllerView_complexModel_rendersViaEl() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/jsp-model")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            String body = resp.body().string();
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + body);
            assertTrue(body.contains("mapName= map-value"), "body=" + body);
            assertTrue(body.contains("first= alpha"), "body=" + body);
            assertTrue(body.contains("userName= zhangsan"), "body=" + body);
        }
    }

    @Test
    void jspInclude_appendsFragment() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/jsp/include.jsp")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            String body = resp.body().string();
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + body);
            assertTrue(body.contains("before-include"), "body=" + body);
            assertTrue(body.contains("frag-content"), "body=" + body);
            assertTrue(body.contains("after-include"), "body=" + body);
        }
    }

    @Test
    void jspForward_rendersTarget() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/jsp/forward.jsp")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            String body = resp.body().string();
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + body);
            assertTrue(body.contains("hello jsp"), "body=" + body);
        }
    }

    @Test
    void jspNotFound_returns404() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/jsp/not-exist.jsp")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertEquals(404, resp.code(), "body=" + resp.body().string());
        }
    }

    @Test
    void controllerView_jstl_rendersLoopAndCondition() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/jsp-jstl")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            String body = resp.body().string();
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + body);
            assertTrue(body.contains("static-ok"), "body=" + body);
            assertTrue(body.contains("<li>a</li>"), "body=" + body);
            assertTrue(body.contains("<li>b</li>"), "body=" + body);
            assertTrue(body.contains("<li>c</li>"), "body=" + body);
            assertTrue(body.contains("flag-on"), "body=" + body);
            assertFalse(body.contains("flag-off"), "body=" + body);
        }
    }
}
