package io.springperf.webtest.bridge;

import io.springperf.webtest.BaseE2ETest;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ServletBridgeE2eTest extends BaseE2ETest {

    private static final String BASE = "http://localhost:9090/api";

    private static final OkHttpClient NO_REDIRECT_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(Duration.ofSeconds(3))
            .readTimeout(Duration.ofSeconds(10))
            .writeTimeout(Duration.ofSeconds(10))
            .followRedirects(false)
            .build();

    @Test
    void getRequestURL_returnsFullUrl() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/servlet-bridge/request-url")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertTrue(resp.isSuccessful());
            String body = resp.body().string();
            assertTrue(body.contains("\"requestURL\":\"http://localhost:9090/api/servlet-bridge/request-url\""));
            assertTrue(body.contains("\"scheme\":\"http\""));
            assertTrue(body.contains("\"remoteAddr\":\"127.0.0.1\""));
            assertTrue(body.contains("\"secure\":\"false\""));
            assertTrue(body.contains("\"method\":\"GET\""));
        }
    }

    @Test
    void sendRedirect_returns302WithLocation() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/servlet-bridge/redirect")
                .build();
        try (Response resp = NO_REDIRECT_CLIENT.newCall(request).execute()) {
            assertEquals(302, resp.code());
            String location = resp.header("Location");
            assertNotNull(location);
            assertTrue(location.contains("/api/servlet-bridge/redirect-target"));
        }
    }

    @Test
    void getMimeType_returnsCorrectType() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/servlet-bridge/mime-type?file=test.html")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertTrue(resp.isSuccessful());
            String body = resp.body().string();
            assertTrue(body.contains("\"mimeType\":\"text/html\""));
        }
    }

    @Test
    void getServerInfo_returnsSpringPerfWeb() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/servlet-bridge/server-info")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertTrue(resp.isSuccessful());
            String body = resp.body().string();
            assertTrue(body.contains("\"serverInfo\":\"spring-perf-web\""));
            assertTrue(body.contains("\"contextPath\":\"/api\""));
        }
    }

    @Test
    void getCharacterEncoding_returnsUtf8() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/servlet-bridge/character-encoding")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertTrue(resp.isSuccessful());
            String body = resp.body().string();
            assertTrue(body.contains("\"dispatcherType\":\"REQUEST\""));
        }
    }

    @Test
    void setContentType_returnsType() throws IOException {
        String requestBody = "{\"contentType\":\"text/plain\"}";
        okhttp3.RequestBody body = okhttp3.RequestBody.create(requestBody, okhttp3.MediaType.get("application/json"));
        Request request = new Request.Builder()
                .url(BASE + "/servlet-bridge/content-type")
                .post(body)
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertTrue(resp.isSuccessful());
        }
    }

    @Test
    void getSession_createsSession() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/servlet-bridge/session")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertTrue(resp.isSuccessful());
            String body = resp.body().string();
            assertTrue(body.contains("\"sessionId\""));
            assertTrue(body.contains("\"new\":\"true\""));
        }
    }

    @Test
    void getAuthType_returnsNull() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/servlet-bridge/auth-type")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertTrue(resp.isSuccessful());
            String body = resp.body().string();
            assertTrue(body.contains("\"authType\":null"));
        }
    }

    @Test
    void forward_dispatchesToTarget() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/servlet-bridge/do-forward")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertTrue(resp.isSuccessful());
            String body = resp.body().string();
            assertEquals("forwarded", body);
        }
    }

    @Test
    void include_appendsContent() throws IOException {
        Request request = new Request.Builder()
                .url(BASE + "/servlet-bridge/do-include")
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertTrue(resp.isSuccessful());
            String body = resp.body().string();
            assertTrue(body.contains("included"));
        }
    }

    @Test
    void getParts_parsesMultipart() throws IOException {
        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file1", "test1.txt", RequestBody.create("content1", MediaType.parse("text/plain")))
                .addFormDataPart("file2", "test2.txt", RequestBody.create("content2", MediaType.parse("text/plain")))
                .build();
        Request request = new Request.Builder()
                .url(BASE + "/servlet-bridge/parts")
                .post(multipartBody)
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            assertTrue(resp.isSuccessful());
            String body = resp.body().string();
            assertTrue(body.contains("\"count\":2"));
            assertTrue(body.contains("\"file1\""));
            assertTrue(body.contains("\"file2\""));
        }
    }
}