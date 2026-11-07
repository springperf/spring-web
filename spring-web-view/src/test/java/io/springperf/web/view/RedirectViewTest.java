package io.springperf.web.view;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedirectViewTest {

    private WebServerHttpRequest mockReq(String contextPath) {
        return mockReq(contextPath, null);
    }

    private WebServerHttpRequest mockReq(String contextPath, String allowedHosts) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebContext wc = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(ViewProperties.REDIRECT_HOSTS, null)).thenReturn(allowedHosts);
        when(wc.getContextPath()).thenReturn(contextPath);
        when(wc.getProps()).thenReturn(props);
        when(req.getWebContext()).thenReturn(wc);
        return req;
    }

    private WebServerHttpResponse mockResp() {
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        when(resp.getHeaders()).thenReturn(headers);
        return resp;
    }

    @Test
    void render_absoluteHttpUrl_usesAsIs() throws Exception {
        RedirectView view = new RedirectView("http://external.com/path");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq("/ctx"), resp);
        verify(resp).setStatusCode(HttpStatus.FOUND);
        assertEquals("http://external.com/path", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_absoluteHttpsUrl_usesAsIs() throws Exception {
        RedirectView view = new RedirectView("https://external.com/path");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq("/ctx"), resp);
        assertEquals("https://external.com/path", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_rootPath_prependsContextPath() throws Exception {
        RedirectView view = new RedirectView("/home");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq("/app"), resp);
        assertEquals("/app/home", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_emptyContextPath_noDoubleSlash() throws Exception {
        // WebContext 默认 contextPath 经 formatPath("/") 归一化为 ""，此处验证空 contextPath 不产生 // 前缀
        RedirectView view = new RedirectView("/home");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq(""), resp);
        assertEquals("/home", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_relativeUrl_prependsContextPathWithSlash() throws Exception {
        RedirectView view = new RedirectView("home");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq("/app"), resp);
        assertEquals("/app/home", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_nullModel_noQueryString() throws Exception {
        RedirectView view = new RedirectView("/home");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq(""), resp);
        assertFalse(resp.getHeaders().getFirst(HttpHeaders.LOCATION).contains("?"));
    }

    @Test
    void render_emptyModel_noQueryString() throws Exception {
        RedirectView view = new RedirectView("/home");
        WebServerHttpResponse resp = mockResp();
        view.render(new LinkedHashMap<>(), mockReq(""), resp);
        assertFalse(resp.getHeaders().getFirst(HttpHeaders.LOCATION).contains("?"));
    }

    @Test
    void render_modelSimpleTypes_buildsQueryString() throws Exception {
        RedirectView view = new RedirectView("/search");
        WebServerHttpResponse resp = mockResp();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("q", "hello world");
        model.put("page", 2);
        model.put("flag", true);
        view.render(model, mockReq(""), resp);
        String location = resp.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertTrue(location.startsWith("/search?"));
        assertTrue(location.contains("q=hello+world"), "q 应 URL 编码，实际: " + location);
        assertTrue(location.contains("page=2"));
        assertTrue(location.contains("flag=true"));
    }

    @Test
    void render_modelNullValue_skipped() throws Exception {
        RedirectView view = new RedirectView("/x");
        WebServerHttpResponse resp = mockResp();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("a", null);
        model.put("b", "1");
        view.render(model, mockReq(""), resp);
        assertFalse(resp.getHeaders().getFirst(HttpHeaders.LOCATION).contains("a="));
        assertTrue(resp.getHeaders().getFirst(HttpHeaders.LOCATION).contains("b=1"));
    }

    @Test
    void render_modelComplexType_skipped() throws Exception {
        RedirectView view = new RedirectView("/x");
        WebServerHttpResponse resp = mockResp();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("obj", new Object());
        model.put("list", java.util.List.of(1, 2));
        view.render(model, mockReq(""), resp);
        assertFalse(resp.getHeaders().getFirst(HttpHeaders.LOCATION).contains("obj="));
        assertFalse(resp.getHeaders().getFirst(HttpHeaders.LOCATION).contains("list="));
    }

    @Test
    void render_modelSpecialChars_urlEncoded() throws Exception {
        RedirectView view = new RedirectView("/x");
        WebServerHttpResponse resp = mockResp();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("k e y", "v&a=b/c");
        view.render(model, mockReq(""), resp);
        String location = resp.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertFalse(location.contains(" "), "空格不应出现在 Location，实际: " + location);
        assertFalse(location.contains("&a"), "值内 & 应被编码，实际: " + location);
    }

    @Test
    void render_modelPrimitiveInt_encoded() throws Exception {
        RedirectView view = new RedirectView("/x");
        WebServerHttpResponse resp = mockResp();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("i", 42);
        view.render(model, mockReq(""), resp);
        assertTrue(resp.getHeaders().getFirst(HttpHeaders.LOCATION).contains("i=42"));
    }

    @Test
    void render_custom3xxStatus_isPreserved() throws Exception {
        RedirectView view = new RedirectView("/home", HttpStatus.MOVED_PERMANENTLY);
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq(""), resp);
        verify(resp).setStatusCode(HttpStatus.MOVED_PERMANENTLY);
        verify(resp, never()).setStatusCode(HttpStatus.FOUND);
    }

    @Test
    void render_nullStatus_fallsBackTo302() throws Exception {
        RedirectView view = new RedirectView("/home", null);
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq(""), resp);
        verify(resp).setStatusCode(HttpStatus.FOUND);
    }

    // ==================== 外部目标与 host 白名单 ====================

    @Test
    void render_protocolRelativeUrl_usesAsIs() throws Exception {
        RedirectView view = new RedirectView("//external.com/path");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq("/app"), resp);
        assertEquals("//external.com/path", resp.getHeaders().getFirst(HttpHeaders.LOCATION),
                "协议相对 URL 是外部目标，不应拼 context-path");
    }

    @Test
    void render_allowedExternalHost_passes() throws Exception {
        RedirectView view = new RedirectView("https://Partner.example.com/path");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq("", "partner.example.com, self.example.com"), resp);
        assertEquals("https://Partner.example.com/path", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_disallowedExternalHost_throws() {
        RedirectView view = new RedirectView("http://evil.com/path");
        WebServerHttpResponse resp = mockResp();
        WebServerHttpRequest req = mockReq("", "trusted.example.com");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> view.render(null, req, resp));
        assertTrue(ex.getMessage().contains(ViewProperties.REDIRECT_HOSTS));
    }

    @Test
    void render_disallowedProtocolRelativeHost_throws() {
        RedirectView view = new RedirectView("//evil.com/path");
        WebServerHttpResponse resp = mockResp();
        WebServerHttpRequest req = mockReq("", "trusted.example.com");
        assertThrows(IllegalArgumentException.class, () -> view.render(null, req, resp),
                "//host 也必须受白名单约束");
    }

    @Test
    void render_userInfoBypass_isRejected() {
        // userinfo 不是 host：trusted.example.com@evil.com 的真实 host 是 evil.com
        RedirectView view = new RedirectView("https://trusted.example.com@evil.com/path");
        WebServerHttpResponse resp = mockResp();
        WebServerHttpRequest req = mockReq("", "trusted.example.com");
        assertThrows(IllegalArgumentException.class, () -> view.render(null, req, resp));
    }

    @Test
    void render_malformedExternalUrl_isRejected() {
        // 畸形 URL 解析失败时也必须拒绝，不能让白名单被绕过
        RedirectView view = new RedirectView("http://[invalid/path");
        WebServerHttpResponse resp = mockResp();
        WebServerHttpRequest req = mockReq("", "trusted.example.com");
        assertThrows(IllegalArgumentException.class, () -> view.render(null, req, resp));
    }

    @Test
    void render_internalPath_ignoresHostWhitelist() throws Exception {
        RedirectView view = new RedirectView("/home");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq("/app", "trusted.example.com"), resp);
        assertEquals("/app/home", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }
}
