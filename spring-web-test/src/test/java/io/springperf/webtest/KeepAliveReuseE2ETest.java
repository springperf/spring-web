package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E: verify HTTP/1.1 keep-alive connection reuse. Using {@code /thickness/conn-id},
 * two sequential requests on the same client should reuse the same TCP connection
 * (same remote port), proving the server keeps connections alive.
 */
public class KeepAliveReuseE2ETest extends BaseE2ETest {

    private String connUrl() {
        return urlApi("/thickness/conn-id");
    }

    @Test
    void sequentialRequests_reuseSameConnection() throws Exception {
        Request req = new Request.Builder().url(connUrl()).get().build();

        int firstPort;
        int secondPort;
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            firstPort = (int) body.get("remotePort");
            assertTrue(firstPort > 0, "应返回真实对端端口");
        }
        try (Response resp = CLIENT.newCall(req).execute()) {
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            secondPort = (int) body.get("remotePort");
        }
        // 同一 CLIENT（默认 keep-alive 连接池）连续请求应复用同一连接
        assertEquals(firstPort, secondPort,
                "keep-alive 连接应被复用，remotePort 应一致（首=" + firstPort + " 次=" + secondPort + "）");
    }

    @Test
    void connectionId_exposesServerPort() throws Exception {
        Request req = new Request.Builder().url(connUrl()).get().build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = JSON.parseObject(resp.body().string(), Map.class);
            assertEquals(serverPort, body.get("serverPort"),
                    "conn-id 应暴露实际绑定端口");
        }
    }
}