package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ErrorScenariosTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api/core";

    @Test
    void testNotFound() throws Exception {
        Request req = new Request.Builder().url(baseUrl + "/nonexistent-path").get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(404, resp.code());
        }
    }

    @Test
    void testMethodNotAllowed() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/header")
                .post(RequestBody.create("", MediaType.parse("text/plain")))
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(405, resp.code());
        }
    }

    @Test
    void testValidationFailure() throws Exception {
        String json = "{\"name\":\"\"}";
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        Request req = new Request.Builder()
                .url(baseUrl + "/validate")
                .post(body)
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(400, resp.code());
        }
    }
}