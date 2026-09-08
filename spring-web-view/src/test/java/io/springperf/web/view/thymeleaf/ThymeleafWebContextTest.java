package io.springperf.web.view.thymeleaf;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.thymeleaf.web.IWebApplication;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.IWebRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThymeleafWebContextTest {

    private WebServerHttpRequest req;
    private WebServerHttpResponse resp;
    private WebContext wc;

    @BeforeEach
    void setUp() {
        req = mock(WebServerHttpRequest.class);
        resp = mock(WebServerHttpResponse.class);
        wc = mock(WebContext.class);
        when(wc.getContextPath()).thenReturn("/app");
        when(req.getWebContext()).thenReturn(wc);
        when(req.getMethodValue()).thenReturn("GET");
        when(req.getURI()).thenReturn(URI.create("http://localhost:8080/app/home?x=1"));
        when(req.getPath()).thenReturn("/home");
        when(req.getLocale()).thenReturn(Locale.US);
        when(req.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.add("Accept", "text/html");
        headers.add("X-Custom", "v1");
        when(req.getHeaders()).thenReturn(headers);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("a", "1");
        params.add("a", "2");
        when(req.getParameterMap()).thenReturn(params);
    }

    private ThymeleafWebContext buildContext(Map<String, Object> model) {
        return new ThymeleafWebContext(model, Locale.US, req, resp);
    }

    @Test
    void getLocale_usesProvidedLocale() {
        ThymeleafWebContext ctx = buildContext(new HashMap<>());
        assertEquals(Locale.US, ctx.getLocale());
    }

    @Test
    void variables_modelBased() {
        Map<String, Object> model = new HashMap<>();
        model.put("name", "Perf");
        ThymeleafWebContext ctx = buildContext(model);
        assertTrue(ctx.containsVariable("name"));
        assertFalse(ctx.containsVariable("missing"));
        assertEquals("Perf", ctx.getVariable("name"));
        assertTrue(ctx.getVariableNames().contains("name"));
    }

    @Test
    void getExchange_returnsExchangeWithRequest() {
        ThymeleafWebContext ctx = buildContext(new HashMap<>());
        IWebExchange exchange = ctx.getExchange();
        assertNotNull(exchange);
        IWebRequest request = exchange.getRequest();
        assertEquals("GET", request.getMethod());
        assertEquals("http", request.getScheme());
        assertEquals("localhost", request.getServerName());
        assertEquals(Integer.valueOf(8080), request.getServerPort());
        assertEquals("/app", request.getApplicationPath());
        assertEquals("/home", request.getPathWithinApplication());
        assertEquals("x=1", request.getQueryString());
    }

    @Test
    void exchange_getLocaleAndCharacterEncoding() {
        ThymeleafWebContext ctx = buildContext(new HashMap<>());
        IWebExchange exchange = ctx.getExchange();
        assertEquals(Locale.US, exchange.getLocale());
        assertEquals("UTF-8", exchange.getCharacterEncoding());
        assertNull(exchange.getContentType());
        assertNull(exchange.getPrincipal());
        assertNull(exchange.getSession());
    }

    @Test
    void request_headers() {
        ThymeleafWebContext ctx = buildContext(new HashMap<>());
        IWebRequest request = ctx.getExchange().getRequest();
        assertTrue(request.containsHeader("Accept"));
        assertTrue(request.containsHeader("X-Custom"));
        assertEquals(2, request.getHeaderCount());
        assertTrue(request.getAllHeaderNames().contains("Accept"));
        assertEquals(1, request.getHeaderValues("Accept").length);
        assertEquals("text/html", request.getHeaderValues("Accept")[0]);
        assertArrayEquals(new String[]{"v1"}, request.getHeaderMap().get("X-Custom"));
        assertEquals(0, request.getHeaderValues("missing").length);
    }

    @Test
    void request_parameters() {
        ThymeleafWebContext ctx = buildContext(new HashMap<>());
        IWebRequest request = ctx.getExchange().getRequest();
        assertTrue(request.containsParameter("a"));
        assertEquals(1, request.getParameterCount());
        assertTrue(request.getAllParameterNames().contains("a"));
        assertEquals(2, request.getParameterValues("a").length);
        assertEquals("1", request.getParameterValues("a")[0]);
        assertEquals(0, request.getParameterValues("missing").length);
        assertArrayEquals(new String[]{"1", "2"}, request.getParameterMap().get("a"));
    }

    @Test
    void request_cookies_empty() {
        ThymeleafWebContext ctx = buildContext(new HashMap<>());
        IWebRequest request = ctx.getExchange().getRequest();
        assertFalse(request.containsCookie("any"));
        assertEquals(0, request.getCookieCount());
        assertTrue(request.getAllCookieNames().isEmpty());
        assertTrue(request.getCookieMap().isEmpty());
        assertEquals(0, request.getCookieValues("any").length);
    }

    @Test
    void exchange_attributes() {
        ThymeleafWebContext ctx = buildContext(new HashMap<>());
        IWebExchange exchange = ctx.getExchange();
        assertFalse(exchange.containsAttribute("k"));
        exchange.setAttributeValue("k", "v");
        assertTrue(exchange.containsAttribute("k"));
        assertEquals("v", exchange.getAttributeValue("k"));
        assertEquals(1, exchange.getAttributeCount());
        assertTrue(exchange.getAllAttributeNames().contains("k"));
        assertTrue(exchange.getAttributeMap().containsKey("k"));
        exchange.removeAttribute("k");
        assertFalse(exchange.containsAttribute("k"));
    }

    @Test
    void exchange_transformURL() {
        ThymeleafWebContext ctx = buildContext(new HashMap<>());
        IWebExchange exchange = ctx.getExchange();
        assertEquals("http://x.com/a", exchange.transformURL("http://x.com/a"));
        assertEquals("https://x.com/a", exchange.transformURL("https://x.com/a"));
        assertEquals("//cdn.com/a", exchange.transformURL("//cdn.com/a"));
        assertEquals("/app/a", exchange.transformURL("/a"));
        assertEquals("/app/a/b", exchange.transformURL("a/b"));
        assertNull(exchange.transformURL(null));
    }

    @Test
    void application_attributesAndResources() {
        ThymeleafWebContext ctx = buildContext(new HashMap<>());
        IWebApplication application = ctx.getExchange().getApplication();
        application.setAttributeValue("app", "x");
        assertTrue(application.containsAttribute("app"));
        assertEquals("x", application.getAttributeValue("app"));
        assertEquals(1, application.getAttributeCount());
        assertTrue(application.getAllAttributeNames().contains("app"));
        assertTrue(application.getAttributeMap().containsKey("app"));
        application.removeAttribute("app");
        assertFalse(application.containsAttribute("app"));

        // templates/hello.html 在测试 classpath 存在
        assertTrue(application.resourceExists("templates/hello.html"));
        assertNotNull(application.getResourceAsStream("templates/hello.html"));
    }
}
