package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.*;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

public class UploadTaskDbReqTest extends BaseE2ETest {

    private static final MediaType TEXT_PLAIN = MediaType.parse("text/plain");
    private final String uploadUrl = "http://localhost:9090/api/upload";

    @Test
    void unannotatedPojo_withAllFields_shouldBindCorrectly() throws Exception {
        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("projectId", "123")
                .addFormDataPart("seq", "1")
                .addFormDataPart("dbVersionList", "1.0,2.0")
                .addFormDataPart("file", "test.sql",
                        RequestBody.create("CREATE TABLE test (id INT);", TEXT_PLAIN))
                .build();

        Request req = new Request.Builder()
                .url(uploadUrl + "/db-req")
                .post(multipartBody)
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.parseObject(
                    Objects.toString(resp.body().string(), "{}"), Map.class);

            assertEquals(123, ((Number) body.get("projectId")).longValue());
            assertEquals(1, ((Number) body.get("seq")).intValue());
            assertEquals("1.0,2.0", body.get("dbVersionList"));
            assertEquals("test.sql", body.get("fileOriginalFilename"));
            assertNotNull(body.get("fileSize"));
        }
    }

    @Test
    void unannotatedPojo_withoutOptionalField_shouldBindSuccessfully() throws Exception {
        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("projectId", "456")
                .addFormDataPart("seq", "2")
                .addFormDataPart("file", "data.sql",
                        RequestBody.create("SELECT 1;", TEXT_PLAIN))
                .build();

        Request req = new Request.Builder()
                .url(uploadUrl + "/db-req")
                .post(multipartBody)
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.parseObject(
                    Objects.toString(resp.body().string(), "{}"), Map.class);

            assertEquals(456, ((Number) body.get("projectId")).longValue());
            assertEquals(2, ((Number) body.get("seq")).intValue());
            assertNull(body.get("dbVersionList"));
            assertEquals("data.sql", body.get("fileOriginalFilename"));
            assertNotNull(body.get("fileSize"));
        }
    }

    @Test
    void unannotatedPojo_requiredFieldsMissing_shouldReturn200WithNullValues() throws Exception {
        // 没有 @Validated/@Valid 注解，@NotNull 不会触发校验，POJO 绑定成功但字段为 null
        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("seq", "1")
                .addFormDataPart("file", "test.sql",
                        RequestBody.create("content", TEXT_PLAIN))
                .build();

        Request req = new Request.Builder()
                .url(uploadUrl + "/db-req")
                .post(multipartBody)
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.parseObject(
                    Objects.toString(resp.body().string(), "{}"), Map.class);
            assertNull(body.get("projectId"));
            assertEquals(1, ((Number) body.get("seq")).intValue());
        }
    }

    @Test
    void validatedEndpoint_withMissingFields_shouldReturn400() throws Exception {
        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("seq", "1")
                .build();

        Request req = new Request.Builder()
                .url(uploadUrl + "/db-req-validated")
                .post(multipartBody)
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(400, resp.code());
        }
    }

    @Test
    void validatedEndpoint_withAllFields_shouldPassValidation() throws Exception {
        RequestBody multipartBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("projectId", "999")
                .addFormDataPart("seq", "10")
                .addFormDataPart("file", "script.sql",
                        RequestBody.create("INSERT INTO test VALUES (1);", TEXT_PLAIN))
                .build();

        Request req = new Request.Builder()
                .url(uploadUrl + "/db-req-validated")
                .post(multipartBody)
                .build();

        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            @SuppressWarnings("unchecked")
            Map<String, Object> body = JSON.parseObject(
                    Objects.toString(resp.body().string(), "{}"), Map.class);

            assertEquals(999, ((Number) body.get("projectId")).longValue());
            assertEquals(10, ((Number) body.get("seq")).intValue());
            assertEquals("script.sql", body.get("fileOriginalFilename"));
        }
    }
}