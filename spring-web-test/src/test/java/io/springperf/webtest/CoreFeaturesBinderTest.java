package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CoreFeaturesBinderTest extends BaseE2ETest {

    private final String baseUrl = "http://localhost:9090/api/binder";

    @Test
    void initBinder_appliesPropertyEditor() throws Exception {
        Request req = new Request.Builder()
                .url(baseUrl + "/test?value=hello")
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(
                    Objects.toString(resp.body().string(), "{}"), Map.class);
            String value = Objects.toString(body.get("value"), "");
            assertTrue(value.startsWith("edited:"),
                    "PropertyEditor should prepend 'edited:' to the value, got: " + value);
            assertTrue(value.contains("hello"),
                    "The original value 'hello' should be present in the edited result, got: " + value);
        }
    }
}