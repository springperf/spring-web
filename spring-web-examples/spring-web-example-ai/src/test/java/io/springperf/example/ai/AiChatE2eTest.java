package io.springperf.example.ai;

import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AiChatController E2E 测试。
 * <p>
 * /ai/chat 和 /ai/chat/stream 需要真实的 API key，默认跳过。
 * 设置环境变量 AI_API_KEY 为非默认值即可启用完整对话测试。
 */
@SpringBootTest(
        classes = AiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AiChatE2eTest {

    private static final String PLACEHOLDER_API_KEY = "sk-your-key-here";

    private TestRestTemplate rest;

    @Autowired
    private NettyHttpServer nettyHttpServer;

    @BeforeEach
    void setUp() {
        int actualPort = nettyHttpServer.getActualPort();
        rest = new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://localhost:" + actualPort));
    }

    static boolean hasRealApiKey() {
        String key = System.getenv("AI_API_KEY");
        return key != null && !key.isEmpty() && !key.equals(PLACEHOLDER_API_KEY);
    }

    @Test
    void modelEndpointShouldReturnConfigInfo() {
        ResponseEntity<Map> resp = rest.getForEntity("/ai/chat/model", Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody()).containsKeys("baseUrl", "model");
    }

    @Test
    void chatEndpointShouldReturnBadRequestWhenMessageMissing() {
        ResponseEntity<String> resp = rest.getForEntity("/ai/chat", String.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(400);
    }

    @Test
    void streamEndpointShouldReturnBadRequestWhenMessageMissing() {
        ResponseEntity<String> resp = rest.getForEntity("/ai/chat/stream", String.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(400);
    }

    @Test
    void chatEndpointShouldReturnResponseWithRealApiKey() {
        assumeTrue(hasRealApiKey(), "跳过对话测试：未配置真实 API key");

        ResponseEntity<String> resp = rest.getForEntity("/ai/chat?message=你好，请用一句话介绍自己", String.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull().isNotEmpty();
    }

    @Test
    void streamEndpointShouldReturnResponseWithRealApiKey() {
        assumeTrue(hasRealApiKey(), "跳过流式对话测试：未配置真实 API key");

        ResponseEntity<String> resp = rest.getForEntity("/ai/chat/stream?message=你好，请用一句话介绍自己", String.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull().isNotEmpty();
    }
}