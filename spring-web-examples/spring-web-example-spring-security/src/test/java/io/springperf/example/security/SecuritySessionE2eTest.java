package io.springperf.example.security;

import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = SecurityApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class SecuritySessionE2eTest {

    private TestRestTemplate rest;

    @Autowired
    private NettyHttpServer nettyHttpServer;

    @BeforeEach
    void setUp() {
        int actualPort = nettyHttpServer.getActualPort();
        rest = new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://localhost:" + actualPort));
    }

    // ==================== session 测试 ====================

    @Test
    void securitySession_createAndReadAttribute() {
        ResponseEntity<Map> createResp = rest.getForEntity(
                "/security/session-set?key=mykey&value=hello-security", Map.class);
        assertThat(createResp.getStatusCodeValue()).isEqualTo(200);

        Map<String, Object> createBody = createResp.getBody();
        assertThat(createBody).isNotNull();
        String sessionId = (String) createBody.get("sessionId");
        assertThat(sessionId).isNotBlank();
        assertThat(createBody.get("setKey")).isEqualTo("mykey");
        assertThat(createBody.get("setValue")).isEqualTo("hello-security");

        String setCookie = createResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("JSESSIONID cookie must be set when session is created")
                .contains("JSESSIONID=" + sessionId);

        String jsessionid = extractJSessionId(setCookie);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionid);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> getResp = rest.exchange(
                "/security/session-get?key=mykey", HttpMethod.GET, entity, Map.class);
        assertThat(getResp.getStatusCodeValue()).isEqualTo(200);

        Map<String, Object> getBody = getResp.getBody();
        assertThat(getBody).isNotNull();
        assertThat(getBody.get("sessionId")).isEqualTo(sessionId);
        assertThat(getBody.get("key")).isEqualTo("mykey");
        assertThat(getBody.get("value")).isEqualTo("hello-security");
    }

    @Test
    void securitySession_infoWithoutSession() {
        ResponseEntity<Map> resp = rest.getForEntity("/security/session-info", Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);

        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("active")).isEqualTo(false);
    }

    @Test
    void securitySession_crossRequestPersistence() {
        ResponseEntity<Map> firstResp = rest.getForEntity(
                "/security/session-set?key=color&value=blue", Map.class);
        String sessionId = (String) firstResp.getBody().get("sessionId");
        String cookie = extractJSessionId(firstResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + cookie);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> secondResp = rest.exchange(
                "/security/session-get?key=color", HttpMethod.GET, entity, Map.class);
        assertThat(secondResp.getBody().get("sessionId")).isEqualTo(sessionId);
        assertThat(secondResp.getBody().get("value")).isEqualTo("blue");
    }

    // ==================== 角色授权测试 ====================

    @Test
    void adminEndpoint_withoutAuth_returns401() {
        ResponseEntity<Map> resp = rest.getForEntity("/security/admin", Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(401);
    }

    @Test
    void adminEndpoint_withAdminRole_returns200() {
        TestRestTemplate adminRest = rest.withBasicAuth("admin", "admin");
        ResponseEntity<Map> resp = adminRest.getForEntity("/security/admin", Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody().get("username")).isEqualTo("admin");
        assertThat(resp.getBody().get("role")).isEqualTo("ADMIN");
    }

    @Test
    void adminEndpoint_withUserRole_returns403() {
        TestRestTemplate userRest = rest.withBasicAuth("user", "user");
        ResponseEntity<Map> resp = userRest.getForEntity("/security/admin", Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(403);
    }

    @Test
    void userEndpoint_withUserRole_returns200() {
        TestRestTemplate userRest = rest.withBasicAuth("user", "user");
        ResponseEntity<Map> resp = userRest.getForEntity("/security/user", Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody().get("username")).isEqualTo("user");
        assertThat(resp.getBody().get("role")).isEqualTo("USER");
    }

    // ==================== 辅助方法 ====================

    private static String extractJSessionId(String setCookie) {
        assertThat(setCookie).isNotBlank();
        for (String part : setCookie.split(";")) {
            part = part.trim();
            if (part.startsWith("JSESSIONID=")) {
                return part.substring("JSESSIONID=".length());
            }
        }
        throw new AssertionError("JSESSIONID not found in Set-Cookie: " + setCookie);
    }
}