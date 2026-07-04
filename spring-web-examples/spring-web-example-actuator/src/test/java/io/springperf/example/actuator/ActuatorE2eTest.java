package io.springperf.example.actuator;

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
        classes = ActuatorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ActuatorE2eTest {

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
    void health_upAndRunning() {
        ResponseEntity<Map> resp = rest.getForEntity("/actuator/health", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    void metrics_containsJvmInfo() {
        ResponseEntity<Map> resp = rest.getForEntity("/actuator/metrics", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody()).containsKey("names");
    }

    @Test
    void metrics_jvmMemoryAvailable() {
        ResponseEntity<Map> resp = rest.getForEntity("/actuator/metrics/jvm.memory.used", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody()).containsKey("measurements");
    }

    @Test
    void demoController_works() {
        ResponseEntity<Map> resp = rest.getForEntity("/demo/hello", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("message")).isEqualTo("Hello Actuator");
        assertThat(resp.getBody()).containsKey("visits");
    }
}