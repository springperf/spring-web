package io.springperf.webtest;

import io.springperf.webtest.common.ShimResponseEntityExceptionHandler;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ControllerEndpointTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api";

    @Autowired(required = false)
    private ShimResponseEntityExceptionHandler shimHandler;

    @Test
    void shimResponseEntityExceptionHandler_beanLoaded() {
        assertNotNull(shimHandler, "ShimResponseEntityExceptionHandler should be loaded as a bean");
    }

    @Test
    void voidReturn_returns204() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/demo/void-test")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(204, resp.code());
        }
    }

    @Test
    void testEchoGet() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/demo/echo?received=hello")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(302, resp.code());
            String body = Objects.toString(resp.body().string(), "");
            assertEquals("hello", body);
        }
    }

    @Test
    void testEchoPost() throws Exception {
        String json = "{\"message\":\"test\"}";
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json);
        Request req = new Request.Builder()
                .url(baseUrl + "/demo/echo")
                .post(body)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(201, resp.code());
        }
    }

    @Test
    void testAsyncEndpoint() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/demo/async")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
        }
    }
}