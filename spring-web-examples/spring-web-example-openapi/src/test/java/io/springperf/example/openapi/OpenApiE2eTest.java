package io.springperf.example.openapi;

import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = OpenApiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class OpenApiE2eTest {

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
    void healthEndpoint() {
        ResponseEntity<Map> resp = rest.getForEntity("/health", Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    void openApiJson_containsAllEndpoints() {
        ResponseEntity<Map> resp = rest.getForEntity("/v3/api-docs", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();

        // Verify OpenAPI metadata
        assertThat(resp.getBody().get("openapi")).isNotNull();
        assertThat(resp.getBody().get("info")).isNotNull();

        // Verify paths contain our endpoints
        Map<String, Object> paths = (Map<String, Object>) resp.getBody().get("paths");
        assertThat(paths).isNotNull();
        assertThat(paths).containsKey("/health");
        assertThat(paths).containsKey("/api/users");
        assertThat(paths).containsKey("/api/users/{id}");
    }

    @Test
    void userCrudEndpoints_work() {
        // Create user
        Map<String, Object> newUser = new HashMap<>();
        newUser.put("name", "test");
        newUser.put("email", "test@test.com");
        newUser.put("age", 25);
        ResponseEntity<Map> createResp = rest.postForEntity("/api/users", newUser, Map.class);
        assertThat(createResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(createResp.getBody().get("id")).isNotNull();
        Number id = (Number) createResp.getBody().get("id");

        // Get user
        ResponseEntity<Map> getResp = rest.getForEntity("/api/users/" + id.longValue(), Map.class);
        assertThat(getResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(getResp.getBody().get("name")).isEqualTo("test");

        // Search by name (returns Map<Long, User>, not an array)
        ResponseEntity<Map> searchResp = rest.getForEntity("/api/users?name=test", Map.class);
        assertThat(searchResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(searchResp.getBody()).isNotNull();
    }

    @Test
    void openApiJson_hasUserTag() {
        ResponseEntity<Map> resp = rest.getForEntity("/v3/api-docs", Map.class);

        assertThat(resp.getBody()).isNotNull();
        // Tags are extracted from controller class name by OpenApiAdapter
        String bodyStr = resp.getBody().toString();
        assertThat(bodyStr).contains("User");
    }
}