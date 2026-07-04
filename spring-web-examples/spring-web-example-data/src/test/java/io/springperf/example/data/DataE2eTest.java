package io.springperf.example.data;

import io.springperf.example.data.model.User;
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
        classes = DataApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class DataE2eTest {

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
    void createAndGetUser_fullLifecycle() {
        // Step 1: Create a user
        User newUser = new User(null, "alice", "alice@example.com", 25);
        ResponseEntity<Map> createResp = rest.postForEntity("/api/users", newUser, Map.class);

        assertThat(createResp.getStatusCodeValue()).isEqualTo(200);
        Map<String, Object> created = createResp.getBody();
        assertThat(created).isNotNull();
        assertThat(created.get("id")).isNotNull();
        Number userId = (Number) created.get("id");
        assertThat(created.get("name")).isEqualTo("alice");

        // Step 2: Get by ID
        ResponseEntity<Map> getResp = rest.getForEntity("/api/users/" + userId.longValue(), Map.class);
        assertThat(getResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(getResp.getBody().get("name")).isEqualTo("alice");

        // Step 3: Update
        User updated = new User(null, "alice-updated", "alice-new@example.com", 26);
        rest.put("/api/users/" + userId.longValue(), updated);

        ResponseEntity<Map> afterUpdate = rest.getForEntity("/api/users/" + userId.longValue(), Map.class);
        assertThat(afterUpdate.getStatusCodeValue()).isEqualTo(200);
        assertThat(afterUpdate.getBody().get("name")).isEqualTo("alice-updated");

        // Step 4: Delete
        ResponseEntity<Map> deleteResp = rest.exchange(
                "/api/users/" + userId.longValue(),
                HttpMethod.DELETE, null, Map.class);
        assertThat(deleteResp.getStatusCodeValue()).isEqualTo(200);

        // Step 5: Verify deletion
        ResponseEntity<Map> afterDelete = rest.getForEntity("/api/users/" + userId.longValue(), Map.class);
        assertThat(afterDelete.getStatusCodeValue()).isEqualTo(400);
        assertThat(((String) afterDelete.getBody().get("message"))).contains("user not found");
    }

    @Test
    void listUsers_afterCreation() {
        User u1 = new User(null, "bob", "bob@test.com", 30);
        User u2 = new User(null, "bobby", "bobby@test.com", 25);
        rest.postForEntity("/api/users", u1, Map.class);
        rest.postForEntity("/api/users", u2, Map.class);

        ResponseEntity<Map[]> resp = rest.getForEntity("/api/users", Map[].class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().length).isGreaterThanOrEqualTo(2);
    }

    @Test
    void searchByName() {
        rest.postForEntity("/api/users", new User(null, "charlie", "c@test.com", 20), Map.class);
        rest.postForEntity("/api/users", new User(null, "chaplin", "chap@test.com", 35), Map.class);

        ResponseEntity<Map[]> resp = rest.getForEntity("/api/users?name=charlie", Map[].class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().length).isEqualTo(1);
    }

    @Test
    void createUser_validationError() {
        User invalid = new User(null, "", "bad-email", -1);

        ResponseEntity<Map> resp = rest.postForEntity("/api/users", invalid, Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(400);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("code")).isEqualTo(400);
    }

    @Test
    void getUser_notFound() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/users/99999", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(400);
        assertThat(((String) resp.getBody().get("message"))).contains("user not found");
    }

    }