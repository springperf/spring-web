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
        assertEquals("/home", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_emptyModel_noQueryString() throws Exception {
        RedirectView view = new RedirectView("/home");
        WebServerHttpResponse resp = mockResp();
        view.render(new LinkedHashMap<>(), mockReq(""), resp);
        assertEquals("/home", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
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
        assertEquals("/search?q=hello+world&page=2&flag=true", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_modelNullValue_skipped() throws Exception {
        RedirectView view = new RedirectView("/x");
        WebServerHttpResponse resp = mockResp();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("a", null);
        model.put("b", "1");
        view.render(model, mockReq(""), resp);
        assertEquals("/x?b=1", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_modelComplexType_skipped() throws Exception {
        RedirectView view = new RedirectView("/x");
        WebServerHttpResponse resp = mockResp();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("obj", new Object());
        model.put("list", java.util.List.of(1, 2));
        view.render(model, mockReq(""), resp);
        assertEquals("/x", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_modelSpecialChars_urlEncoded() throws Exception {
        RedirectView view = new RedirectView("/x");
        WebServerHttpResponse resp = mockResp();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("k e y", "v&a=b/c");
        view.render(model, mockReq(""), resp);
        assertEquals("/x?k+e+y=v%26a%3Db%2Fc", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_modelPrimitiveInt_encoded() throws Exception {
        RedirectView view = new RedirectView("/x");
        WebServerHttpResponse resp = mockResp();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("i", 42);
        view.render(model, mockReq(""), resp);
        assertEquals("/x?i=42", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
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
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> view.render(null, req, resp));
        assertTrue(ex.getMessage().contains(ViewProperties.REDIRECT_HOSTS));
    }

    @Test
    void render_disallowedProtocolRelativeHost_throws() {
        RedirectView view = new RedirectView("//evil.com/path");
        WebServerHttpResponse resp = mockResp();
        WebServerHttpRequest req = mockReq("", "trusted.example.com");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> view.render(null, req, resp),
                "//host 也必须受白名单约束");
        assertTrue(ex.getMessage().contains(ViewProperties.REDIRECT_HOSTS), "必须是白名单拒绝，而不是别的 IAE: " + ex.getMessage());
    }

    @Test
    void render_userInfoBypass_isRejected() {
        // userinfo 不是 host：trusted.example.com@evil.com 的真实 host 是 evil.com
        RedirectView view = new RedirectView("https://trusted.example.com@evil.com/path");
        WebServerHttpResponse resp = mockResp();
        WebServerHttpRequest req = mockReq("", "trusted.example.com");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> view.render(null, req, resp));
        assertTrue(ex.getMessage().contains(ViewProperties.REDIRECT_HOSTS), "必须是白名单拒绝，而不是别的 IAE: " + ex.getMessage());
    }

    @Test
    void render_malformedExternalUrl_isRejected() {
        // 畸形 URL 解析失败时也必须拒绝，不能让白名单被绕过
        RedirectView view = new RedirectView("http://[invalid/path");
        WebServerHttpResponse resp = mockResp();
        WebServerHttpRequest req = mockReq("", "trusted.example.com");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> view.render(null, req, resp));
        assertTrue(ex.getMessage().contains(ViewProperties.REDIRECT_HOSTS),
                "必须是白名单拒绝，而不是 URI 解析异常直接冒泡: " + ex.getMessage());
    }

    @Test
    void render_internalPath_ignoresHostWhitelist() throws Exception {
        RedirectView view = new RedirectView("/home");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq("/app", "trusted.example.com"), resp);
        assertEquals("/app/home", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    // ==================== 白名单解析缓存 ====================

    @Test
    void render_repeatedWhitelist_reusesParsedHosts() throws Exception {
        // 同一配置串连续渲染：第二次起走缓存，结果必须与首次一致
        RedirectView view = new RedirectView("https://partner.example.com/path");
        for (int i = 0; i < 3; i++) {
            WebServerHttpResponse resp = mockResp();
            view.render(null, mockReq("", "partner.example.com, self.example.com"), resp);
            assertEquals("https://partner.example.com/path", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
        }
    }

    @Test
    void render_whitelistChangedBetweenRequests_newValueTakesEffect() {
        // 以配置串为缓存键，配置热更新后必须按新值判定，不能被上一次的解析结果固化
        RedirectView allowed = new RedirectView("https://a.example.com/path");
        RedirectView other = new RedirectView("https://b.example.com/path");

        assertDoesNotThrow(() -> allowed.render(null, mockReq("", "a.example.com"), mockResp()));
        assertThrows(IllegalArgumentException.class, () -> other.render(null, mockReq("", "a.example.com"), mockResp()),
                "b.example.com 不在原白名单内，必须拒绝");
        assertDoesNotThrow(() -> other.render(null, mockReq("", "b.example.com"), mockResp()),
                "换成 b.example.com 白名单后必须立即放行");
    }

    @Test
    void render_blankWhitelist_treatedAsUnconfigured() throws Exception {
        RedirectView view = new RedirectView("https://any.example.com/path");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq("", "   "), resp);
        assertEquals("https://any.example.com/path", resp.getHeaders().getFirst(HttpHeaders.LOCATION),
                "全空白配置等价于未配置，不限制外部目标");
    }

    @Test
    void render_whitelistOnlySeparators_treatedAsUnconfigured() throws Exception {
        RedirectView view = new RedirectView("https://any.example.com/path");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq("", ",,"), resp);
        assertEquals("https://any.example.com/path", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_whitelistWithSpacesAndMixedCase_normalized() throws Exception {
        RedirectView view = new RedirectView("https://Partner.Example.com/path");
        WebServerHttpResponse resp = mockResp();
        view.render(null, mockReq("", "  PARTNER.example.COM , self.example.com  "), resp);
        assertEquals("https://Partner.Example.com/path", resp.getHeaders().getFirst(HttpHeaders.LOCATION));
    }

    @Test
    void render_whitespaceAboveAsciiSpace_isNotTreatedAsUnconfigured() {
        // 判定范围与 String.trim 一致（<= U+0020 才算空白）。'\u2003' 属于配置了一个不可能匹配的 host，
        // 结果必须是「拒绝」而不是「当作未配置放行」——失败方向要朝安全的一侧关。
        RedirectView view = new RedirectView("https://any.example.com/path");
        assertThrows(IllegalArgumentException.class, () -> view.render(null, mockReq("", "\u2003"), mockResp()));
    }
}
