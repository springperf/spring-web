package io.springperf.example.shiro;

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
        classes = ShiroApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ShiroSessionE2eTest {

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
    void shiroSession_createAndReadAttribute() {
        ResponseEntity<Map> createResp = rest.getForEntity(
                "/shiro/session-set?key=mykey&value=hello-shiro", Map.class);
        assertThat(createResp.getStatusCodeValue()).isEqualTo(200);

        Map<String, Object> createBody = createResp.getBody();
        assertThat(createBody).isNotNull();
        String sessionId = (String) createBody.get("sessionId");
        assertThat(sessionId).isNotBlank();
        assertThat(createBody.get("setKey")).isEqualTo("mykey");
        assertThat(createBody.get("setValue")).isEqualTo("hello-shiro");

        String setCookie = createResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("JSESSIONID cookie must be set when session is created")
                .contains("JSESSIONID=" + sessionId);

        String jsessionid = extractJSessionId(setCookie);

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionid);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> getResp = rest.exchange(
                "/shiro/session-get?key=mykey", HttpMethod.GET, entity, Map.class);
        assertThat(getResp.getStatusCodeValue()).isEqualTo(200);

        Map<String, Object> getBody = getResp.getBody();
        assertThat(getBody).isNotNull();
        assertThat(getBody.get("sessionId")).isEqualTo(sessionId);
        assertThat(getBody.get("key")).isEqualTo("mykey");
        assertThat(getBody.get("value")).isEqualTo("hello-shiro");
    }

    @Test
    void shiroSession_infoWithoutSession() {
        ResponseEntity<Map> resp = rest.getForEntity("/shiro/session-info", Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);

        Map<String, Object> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("active")).isEqualTo(false);
    }

    @Test
    void shiroSession_crossRequestPersistence() {
        ResponseEntity<Map> firstResp = rest.getForEntity(
                "/shiro/session-set?key=color&value=blue", Map.class);
        String sessionId = (String) firstResp.getBody().get("sessionId");
        String cookie = extractJSessionId(firstResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + cookie);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> secondResp = rest.exchange(
                "/shiro/session-get?key=color", HttpMethod.GET, entity, Map.class);
        assertThat(secondResp.getBody().get("sessionId")).isEqualTo(sessionId);
        assertThat(secondResp.getBody().get("value")).isEqualTo("blue");
    }

    // ==================== 认证测试 ====================

    @Test
    void login_withCorrectCredentials_succeeds() {
        ResponseEntity<Map> resp = rest.exchange(
                "/shiro/login?username=admin&password=admin123",
                HttpMethod.GET, HttpEntity.EMPTY, Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody().get("success")).isEqualTo(true);
        assertThat(resp.getBody().get("username")).isEqualTo("admin");
        assertThat((String) resp.getBody().get("sessionId")).isNotBlank();
    }

    @Test
    void login_withWrongCredentials_returns401() {
        ResponseEntity<Map> resp = rest.getForEntity(
                "/shiro/login?username=admin&password=wrong", Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(401);
        assertThat(resp.getBody().get("success")).isEqualTo(false);
    }

    @Test
    void needAuth_withoutLogin_returns401() {
        ResponseEntity<Map> resp = rest.getForEntity("/shiro/need-auth", Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(401);
        assertThat(resp.getBody().get("error")).isEqualTo("Authentication required");
    }

    @Test
    void needAuth_afterLogin_succeeds() {
        // Login
        ResponseEntity<Map> loginResp = rest.getForEntity(
                "/shiro/login?username=admin&password=admin123", Map.class);
        String jsessionid = extractJSessionId(loginResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE));

        // Access protected endpoint with session cookie
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionid);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> authResp = rest.exchange(
                "/shiro/need-auth", HttpMethod.GET, entity, Map.class);
        assertThat(authResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(authResp.getBody().get("authenticated")).isEqualTo(true);
        assertThat(authResp.getBody().get("username")).isEqualTo("admin");
    }

    // ==================== 角色测试 ====================

    @Test
    void roleCheck_adminHasAdminRole() {
        // Login as admin
        ResponseEntity<Map> loginResp = rest.getForEntity(
                "/shiro/login?username=admin&password=admin123", Map.class);
        String jsessionid = extractJSessionId(loginResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionid);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> roleResp = rest.exchange(
                "/shiro/need-role?role=admin", HttpMethod.GET, entity, Map.class);
        assertThat(roleResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(roleResp.getBody().get("granted")).isEqualTo(true);
    }

    @Test
    void roleCheck_userDoesNotHaveAdminRole() {
        ResponseEntity<Map> loginResp = rest.getForEntity(
                "/shiro/login?username=user&password=user123", Map.class);
        String jsessionid = extractJSessionId(loginResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionid);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> roleResp = rest.exchange(
                "/shiro/need-role?role=admin", HttpMethod.GET, entity, Map.class);
        assertThat(roleResp.getStatusCodeValue()).isEqualTo(403);
        assertThat(roleResp.getBody().get("error")).isEqualTo("Missing role: admin");
    }

    // ==================== 权限测试 ====================

    @Test
    void permCheck_adminHasCreatePerm() {
        ResponseEntity<Map> loginResp = rest.getForEntity(
                "/shiro/login?username=admin&password=admin123", Map.class);
        String jsessionid = extractJSessionId(loginResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionid);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> permResp = rest.exchange(
                "/shiro/need-perm?perm=user:create", HttpMethod.GET, entity, Map.class);
        assertThat(permResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(permResp.getBody().get("granted")).isEqualTo(true);
    }

    @Test
    void permCheck_userDoesNotHaveCreatePerm() {
        ResponseEntity<Map> loginResp = rest.getForEntity(
                "/shiro/login?username=user&password=user123", Map.class);
        String jsessionid = extractJSessionId(loginResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionid);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        ResponseEntity<Map> permResp = rest.exchange(
                "/shiro/need-perm?perm=user:create", HttpMethod.GET, entity, Map.class);
        assertThat(permResp.getStatusCodeValue()).isEqualTo(403);
        assertThat(permResp.getBody().get("error")).isEqualTo("Missing permission: user:create");
    }

    // ==================== 登出测试 ====================

    @Test
    void logout_invalidatesSession() {
        // Login
        ResponseEntity<Map> loginResp = rest.getForEntity(
                "/shiro/login?username=admin&password=admin123", Map.class);
        String jsessionid = extractJSessionId(loginResp.getHeaders().getFirst(HttpHeaders.SET_COOKIE));

        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, "JSESSIONID=" + jsessionid);
        HttpEntity<Void> entity = new HttpEntity<>(headers);

        // Logout
        ResponseEntity<Map> logoutResp = rest.exchange(
                "/shiro/logout", HttpMethod.GET, entity, Map.class);
        assertThat(logoutResp.getStatusCodeValue()).isEqualTo(200);
        assertThat(logoutResp.getBody().get("success")).isEqualTo(true);

        // Subsequent access with same cookie should be rejected
        ResponseEntity<Map> authResp = rest.exchange(
                "/shiro/need-auth", HttpMethod.GET, entity, Map.class);
        assertThat(authResp.getStatusCodeValue()).isEqualTo(401);
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