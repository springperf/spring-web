package io.springperf.webtest;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class ErrorScenariosTest extends BaseE2ETest {

    private String baseUrl() {
        return url("/api/core");
    }

    @Test
    void testValidationFailure() throws Exception {
        String json = "{\"name\":\"\"}";
        RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
        Request req = new Request.Builder().url(baseUrl() + "/validate").post(body).build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(400, resp.code());
            String errorBody = resp.body().string();
            org.junit.jupiter.api.Assertions.assertTrue(errorBody.contains("400"), "校验失败响应应标明状态码，实际: " + errorBody);
        }

        // 对照：同一端点合法输入必须成功并回显 name。
        // 只判 400 无法发现"校验规则被改成其它字段 / 校验被整体绕过"这类回归。
        // ValidObj 还有 @NotNull age，合法输入必须两个字段都给
        RequestBody valid = RequestBody.create("{\"name\":\"ok\",\"age\":1}", MediaType.parse("application/json"));
        Request validReq = new Request.Builder().url(baseUrl() + "/validate").post(valid).build();
        try (Response resp = CLIENT.newCall(validReq).execute()) {
            assertEquals(200, resp.code());
            String okBody = resp.body().string();
            assertEquals("ok", com.alibaba.fastjson2.JSON.parseObject(okBody).getString("name"),
                    "合法输入应回显 name，实际: " + okBody);
            assertEquals(1, com.alibaba.fastjson2.JSON.parseObject(okBody).getIntValue("age"),
                    "合法输入应回显 age，实际: " + okBody);
        }
    }
}
