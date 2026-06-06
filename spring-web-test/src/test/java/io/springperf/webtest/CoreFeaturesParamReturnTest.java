package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

public class CoreFeaturesParamReturnTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api/core";

    @Test
    void testRequestHeader() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/header")
                .header("X-Custom-Header", "my-value")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = Objects.toString(resp.body().string(), "");
            assertTrue(body.contains("xCustomHeader") || body.contains("userAgent"));
        }
    }

    @Test
    void testHttpEntity() throws Exception {
        RequestBody body = RequestBody.create("hello entity", MediaType.parse("text/plain"));
        Request req = new Request.Builder()
                .url(baseUrl + "/http-entity")
                .post(body)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String responseBody = resp.body().string();
            assertTrue(responseBody.contains("received: hello entity"));
        }
    }

    @Test
    void testDeferredResult() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/deferred-result").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void testCallable() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/callable").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void testListenableFuture() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/listenable-future").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }

    @Test
    void testBytes() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/bytes").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("Hello, Bytes!", body);
        }
    }

    @Test
    void testResource() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/resource").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            assertNotNull(resp.header("Content-Type"));
        }
    }

    @Test
    void testInputStream() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/input-stream").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertEquals("Hello, Stream!", body);
        }
    }

    @Test
    void voidReturn_returns204() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/void")
                .post(RequestBody.create(MediaType.parse("text/plain"), ""))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(204, resp.code());
        }
    }
}