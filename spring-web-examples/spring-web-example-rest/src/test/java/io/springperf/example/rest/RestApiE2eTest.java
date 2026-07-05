package io.springperf.example.rest;

import io.springperf.example.rest.model.User;
import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = RestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class RestApiE2eTest {

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
    void health_returnsOk() {
        ResponseEntity<Map> resp = rest.getForEntity("/health", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo(0);
        assertThat(resp.getBody().get("data")).isEqualTo("OK");
    }

    @Test
    void listUsers_initiallyEmpty() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/users", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo(0);
        // data is an empty Map
        assertThat(resp.getBody().get("data")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) resp.getBody().get("data"))).isEmpty();
    }

    @Test
    void createAndGetUser_fullLifecycle() {
        // Step 1: Create a user
        User newUser = new User(null, "alice", "alice@example.com", 25);
        ResponseEntity<Map> createResp = rest.postForEntity("/api/users", newUser, Map.class);

        assertThat(createResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(createResp.getBody()).isNotNull();
        assertThat(createResp.getBody().get("code")).isEqualTo(0);
        assertThat(createResp.getBody().get("message")).isEqualTo("success");

        // Extract created user data
        Map<String, Object> createdUser = (Map<String, Object>) createResp.getBody().get("data");
        assertThat(createdUser).isNotNull();
        assertThat(createdUser.get("id")).isNotNull();
        Number userId = (Number) createdUser.get("id");
        assertThat(createdUser.get("name")).isEqualTo("alice");
        assertThat(createdUser.get("email")).isEqualTo("alice@example.com");
        assertThat(createdUser.get("age")).isEqualTo(25);

        // Step 2: Get the user by ID
        ResponseEntity<Map> getResp = rest.getForEntity("/api/users/" + userId.longValue(), Map.class);
        assertThat(getResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(getResp.getBody()).isNotNull();
        assertThat(getResp.getBody().get("code")).isEqualTo(0);

        Map<String, Object> fetchedUser = (Map<String, Object>) getResp.getBody().get("data");
        assertThat(fetchedUser).isNotNull();
        assertThat(fetchedUser.get("id")).isEqualTo(userId);
        assertThat(fetchedUser.get("name")).isEqualTo("alice");

        // Step 3: Update the user
        User updatedUser = new User(null, "alice-updated", "alice-new@example.com", 26);
        rest.put("/api/users/" + userId.longValue(), updatedUser);

        // Verify update
        ResponseEntity<Map> getAfterUpdate = rest.getForEntity("/api/users/" + userId.longValue(), Map.class);
        assertThat(getAfterUpdate.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> afterUpdate = (Map<String, Object>) getAfterUpdate.getBody().get("data");
        assertThat(afterUpdate.get("name")).isEqualTo("alice-updated");
        assertThat(afterUpdate.get("email")).isEqualTo("alice-new@example.com");

        // Step 4: Delete the user
        // Need to use exchange for DELETE with response body
        ResponseEntity<Map> deleteResp = rest.exchange(
                "/api/users/" + userId.longValue(),
                HttpMethod.DELETE, null, Map.class);
        assertThat(deleteResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(deleteResp.getBody()).isNotNull();
        assertThat(deleteResp.getBody().get("code")).isEqualTo(0);

        // Step 5: Verify deletion — user is gone
        ResponseEntity<Map> getAfterDelete = rest.getForEntity("/api/users/" + userId.longValue(), Map.class);
        assertThat(getAfterDelete.getStatusCodeValue()).isEqualTo(200);
        assertThat(getAfterDelete.getBody()).isNotNull();
        assertThat(getAfterDelete.getBody().get("code")).isEqualTo(404);
    }

    @Test
    void getUser_notFound_returns404() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/users/99999", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200); // ApiResult always 200
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo(404);
        assertThat(resp.getBody().get("message")).isEqualTo("user not found");
    }

    @Test
    void deleteUser_notFound_returns404() {
        ResponseEntity<Map> resp = rest.exchange(
                "/api/users/99999", HttpMethod.DELETE, null, Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo(404);
        assertThat(resp.getBody().get("message")).isEqualTo("user not found");
    }
}