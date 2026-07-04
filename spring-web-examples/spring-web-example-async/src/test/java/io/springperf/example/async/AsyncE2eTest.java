package io.springperf.example.async;

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

@SpringBootTest(
        classes = AsyncApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class AsyncE2eTest {

    private TestRestTemplate rest;

    @Autowired
    private NettyHttpServer nettyHttpServer;

    @BeforeEach
    void setUp() {
        int actualPort = nettyHttpServer.getActualPort();
        rest = new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://localhost:" + actualPort));
    }

    @Test
    void deferredResult_returnsAsyncResult() {
        ResponseEntity<Map> resp = rest.getForEntity("/async/deferred-result", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("status")).isEqualTo("async-ok");
        assertThat(resp.getBody().get("from")).isEqualTo("deferred-result");
    }

    @Test
    void deferredResultError_returnsErrorResponse() {
        ResponseEntity<Map> resp = rest.getForEntity("/async/deferred-result-error", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(500);
        assertThat(resp.getBody()).isNotNull();
        assertThat(((String) resp.getBody().get("message"))).contains("async-error-occurred");
    }

    @Test
    void callable_returnsResult() {
        ResponseEntity<Map> resp = rest.getForEntity("/async/callable", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("from")).isEqualTo("callable");
    }

    @Test
    void eventLoopThread() {
        ResponseEntity<Map> eventLoopResp = rest.getForEntity("/thread/event-loop", Map.class);

        assertThat(eventLoopResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(eventLoopResp.getBody()).isNotNull();
        String eventLoopThread = (String) eventLoopResp.getBody().get("thread");
        // Thread name is like "nioEventLoopGroup-3-1"
        assertThat(eventLoopThread).contains("EventLoop");
    }

    @Test
    void bizPoolThread() {
        ResponseEntity<Map> bizResp = rest.getForEntity("/thread/biz-pool", Map.class);

        assertThat(bizResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(bizResp.getBody()).isNotNull();
        String threadName = (String) bizResp.getBody().get("thread");
        assertThat(threadName).doesNotContain("eventLoop");
    }
}