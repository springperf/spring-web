package io.springperf.web.view;

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
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebContext wc = mock(WebContext.class);
        when(wc.getContextPath()).thenReturn(contextPath);
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
        // WebContext 榛樿 contextPath 缁?formatPath("/") 褰掍竴鍖栦负 ""锛屾澶勯獙璇佺┖ contextPath 涓嶄骇鐢?// 鍓嶇紑
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
        assertTrue(location.contains("q=hello+world"), "q 搴?URL 缂栫爜锛屽疄闄? " + location);
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
        model.put("list", Arrays.asList(1, 2));
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
        assertFalse(location.contains(" "), "绌烘牸涓嶅簲鍑虹幇鍦?Location锛屽疄闄? " + location);
        assertFalse(location.contains("&a"), "鍊煎唴 & 搴旇缂栫爜锛屽疄闄? " + location);
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
}
