package io.springperf.example.ai;

import io.springperf.example.support.TestRestTemplate;
import io.springperf.example.support.RestTemplateBuilder;

import io.springperf.web.core.metrics.CountingWebMetrics;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code /ai/chat} 与 {@code /ai/chat/stream} 的**离线**确定性 E2E：用替身 {@link ChatClient} 替换真实模型，使「Spring AI 的
 * {@code Flux<String>} → 框架响应式路径（{@code TextStreamEmitter}）」 这条最易回归的链路在无网络、无 API key 的情况下也能被回归覆盖。
 * <p>
 * 动机：{@link AiChatE2eTest} 的真实对话用例以 {@code assumeTrue(hasRealApiKey())} 门控， 未配置 key 时整条流式路径无人覆盖（默认 CI 即为该情形）。本类与之互补：
 * AiChatE2eTest 负责「真模型可达」，本类负责「框架流式语义正确」。
 * </p>
 */
@SpringBootTest(classes = { AiApplication.class,
        AiChatStreamOfflineE2eTest.StubChatClientConfig.class }, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        // 随机端口：application.yml 固定 server.port=8080，而 Spring TestContext 会缓存并保持
        // 前一个上下文（AiChatE2eTest）存活 → 同 JVM 内两个上下文都绑 8080 时后者必然启动失败
        // （全量运行时实测 "Failed to load ApplicationContext"，单独运行时不暴露）。
        properties = "server.port=0")
class AiChatStreamOfflineE2eTest {

    private static final String ANSWER = "STUB-ANSWER";
    private static final String[] TOKENS = { "token-1", "token-2", "token-3" };

    private TestRestTemplate rest;

    @Autowired
    private NettyHttpServer nettyHttpServer;

    /**
     * 本 context 的计量组件（由 {@link StubChatClientConfig} 注册 {@link CountingWebMetrics}）：在飞异步生命周期 计数取自它，而不再是全局静态字段——全局读数会被别的
     * context 的残留污染。
     */
    @Autowired
    private WebMetrics webMetrics;

    /** 在飞的异步生命周期数（语义同原先的全局读数，作用域为本 context）。 */
    private int activeRequestRefs() {
        if (!(webMetrics instanceof CountingWebMetrics counting)) {
            throw new IllegalStateException("本类需要可读计量实现（CountingWebMetrics），实际装配为 " + webMetrics.getClass().getName());
        }
        return counting.activeAsyncLifecycles();
    }

    /**
     * 覆盖自动配置的 {@code ChatClient.Builder}（{@code @Primary}）：控制器本就只依赖这一层间接
     * （{@code chatClientBuilder.build()}），因此**无需改动生产代码**即可注入替身客户端。
     */
    @TestConfiguration
    static class StubChatClientConfig {

        /** 可读计量实现（默认装配 {@code NoOpWebMetrics} 读不出计数，而本类要断言它归零）。 */
        @Bean
        WebMetrics countingWebMetrics() {
            return new CountingWebMetrics();
        }

        @Bean
        @Primary
        ChatClient.Builder stubChatClientBuilder() {
            ChatClient client = mock(ChatClient.class, RETURNS_DEEP_STUBS);
            when(client.prompt().user(anyString()).call().content()).thenReturn(ANSWER);
            when(client.prompt().user(anyString()).stream().content()).thenReturn(Flux.just(TOKENS.clone()));
            ChatClient.Builder builder = mock(ChatClient.Builder.class);
            when(builder.build()).thenReturn(client);
            return builder;
        }
    }

    @BeforeEach
    void setUp() {
        rest = new TestRestTemplate(
                new RestTemplateBuilder().rootUri("http://localhost:" + nettyHttpServer.getActualPort()));
    }

    @Test
    void chat_returnsStubAnswer() {
        ResponseEntity<String> resp = rest.getForEntity("/ai/chat?message=hi", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo(ANSWER);
    }

    /**
     * 流式：3 个 token 必须全部到达、顺序保持、读到 EOF（生命周期正常终结），且异步持有者引用归零。
     */
    @Test
    void chatStream_emitsAllTokensInOrder_andLifecycleTerminates() {
        ResponseEntity<String> resp = rest.getForEntity("/ai/chat/stream?message=hi", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);

        String body = resp.getBody();
        assertThat(body).as("流式响应体不应为空").isNotNull().isNotEmpty();
        for (String token : TOKENS) {
            assertThat(body).as("token 必须到达：%s（实际=%s）", token, body).contains(token);
        }
        assertThat(body.indexOf(TOKENS[0])).as("token 顺序必须保持（实际=%s）", body).isLessThan(body.indexOf(TOKENS[1]));
        assertThat(body.indexOf(TOKENS[1])).as("token 顺序必须保持（实际=%s）", body).isLessThan(body.indexOf(TOKENS[2]));

        assertThat(activeRequestRefs()).as("流式结束后异步持有者引用必须归零（残留即有未终结的异步生命周期）").isZero();
    }

    /** 同一连接的第二次流式请求：验证终结后可复用（无残留状态）。 */
    @Test
    void chatStream_isRepeatableOnSameServer() {
        String first = rest.getForEntity("/ai/chat/stream?message=a", String.class).getBody();
        String second = rest.getForEntity("/ai/chat/stream?message=b", String.class).getBody();
        assertThat(first).isEqualTo(second);
        assertThat(activeRequestRefs()).isZero();
    }
}
