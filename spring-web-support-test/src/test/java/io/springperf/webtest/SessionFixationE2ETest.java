package io.springperf.webtest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.FormBody;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E 安全验证：登录成功后必须轮换 Session ID（会话固定防护）。
 * <p>攻击场景：攻击者先给受害者种下已知 session ID（如跨子域 Cookie），
 * 受害者登录后若 session ID 不变，攻击者可接管已认证会话。</p>
 */
public class SessionFixationE2ETest extends BaseE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {};

    private String baseUrl() {
        return url("/api");
    }

    private Map<String, Object> parseBody(String json) throws Exception {
        return MAPPER.readValue(json, MAP_TYPE);
    }

    @Test
    void login_afterPrewarmedSession_rotatesSessionId() throws Exception {
        // 1. 模拟攻击者预置的 session：先访问创建 session 的端点，记录旧 ID（即攻击者已知的 JSESSIONID）
        Request prewarm = new Request.Builder()
                .url(baseUrl() + "/servlet-bridge/session")
                .get()
                .build();
        String oldSessionId;
        String oldCookie = null;
        try (Response resp = CLIENT.newCall(prewarm).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = parseBody(resp.body().string());
            oldSessionId = String.valueOf(body.get("sessionId"));
            assertNotNull(oldSessionId);
            // 捕获服务端种下的 session cookie，登录请求必须携带它（模拟攻击者预置）
            java.util.List<String> cookies = resp.headers("Set-Cookie");
            for (String c : cookies) {
                if (c.startsWith("JSESSIONID=")) {
                    oldCookie = c.split(";")[0];
                    break;
                }
            }
        }
        assertNotNull(oldCookie, "预置 session 应返回 JSESSIONID cookie");

        // 2. 携带攻击者预置的 session cookie 登录（若未轮换，攻击者可接管已认证会话）
        FormBody form = new FormBody.Builder()
                .add("username", "user")
                .add("password", "secret")
                .build();
        Request loginReq = new Request.Builder()
                .url(baseUrl() + "/servlet-bridge/login")
                .header("Cookie", oldCookie)
                .post(form)
                .build();
        String postLoginSessionId;
        try (Response resp = CLIENT.newCall(loginReq).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = parseBody(resp.body().string());
            postLoginSessionId = String.valueOf(body.get("sessionId"));
            assertEquals("user", body.get("remoteUser"), "登录成功后应返回认证用户");
        }

        // 3. 会话固定防护：携带预置 session 登录后，session ID 必须被轮换为新值
        assertNotEquals(oldSessionId, postLoginSessionId,
                "携带攻击者预置 session 登录后，session ID 必须轮换，否则存在会话固定漏洞");
    }

    @Test
    void login_withWrongCredentials_fails() throws Exception {
        FormBody form = new FormBody.Builder()
                .add("username", "user")
                .add("password", "wrong")
                .build();
        Request loginReq = new Request.Builder()
                .url(baseUrl() + "/servlet-bridge/login")
                .post(form)
                .build();
        try (Response resp = CLIENT.newCall(loginReq).execute()) {
            // 认证失败：应用内 Throwable 兜底映射为 200 + 内部错误业务码（INTERNAL_SERVER_ERROR）
            Map<String, Object> body = parseBody(resp.body().string());
            Object code = body.get("code");
            assertNotEquals("0", String.valueOf(code), "认证失败不应返回成功码");
        }
    }

    @Test
    void logout_clearsPrincipal() throws Exception {
        FormBody form = new FormBody.Builder()
                .add("username", "user")
                .add("password", "secret")
                .build();
        try (Response loginResp = CLIENT.newCall(new Request.Builder()
                .url(baseUrl() + "/servlet-bridge/login")
                .post(form)
                .build()).execute()) {
            assertEquals(200, loginResp.code());
        }

        Request logoutReq = new Request.Builder()
                .url(baseUrl() + "/servlet-bridge/logout")
                .post(new FormBody.Builder().build())
                .build();
        try (Response resp = CLIENT.newCall(logoutReq).execute()) {
            assertEquals(200, resp.code());
            Map<String, Object> body = parseBody(resp.body().string());
            assertNull(body.get("remoteUser"), "logout 后 remoteUser 应为 null");
        }
    }
}