package io.springperf.web.support.view;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.Ordered;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.IWebSession;

import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link ServletWebExchangeProvider} 及 Servlet session/principal/cookie 桥接。
 */
@ExtendWith(MockitoExtension.class)
class ServletWebExchangeProviderTest {

    @Mock WebServerHttpRequest req;
    @Mock WebServerHttpResponse resp;
    @Mock WebContext webContext;
    @Mock RequestContext requestContext;
    @Mock ServletAdapterContext adapterContext;
    @Mock HttpServletRequest servletRequest;

    private final Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
    private ServletWebExchangeProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ServletWebExchangeProvider();
        lenient().when(req.getWebContext()).thenReturn(webContext);
        lenient().when(webContext.getContextPath()).thenReturn("/app");
    }

    private void bindAdapter() {
        lenient().when(requestContext.getAttribute(any(RequestAttribute.class)))
                .thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        lenient().doAnswer(inv -> {
            fastAttrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());
        lenient().when(req.getRequestContext()).thenReturn(requestContext);
        ServletAttribute.setAdapterContext(requestContext, adapterContext);
        lenient().when(adapterContext.getRequest()).thenReturn(servletRequest);
    }

    // ==================== provider 契约 ====================

    @Test
    void order_isHighestPrecedence() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE, provider.getOrder());
    }

    @Test
    void supports_requestWithContext_true() {
        when(req.getRequestContext()).thenReturn(requestContext);
        assertTrue(provider.supports(req));
    }

    @Test
    void supports_nullRequest_false() {
        assertFalse(provider.supports(null));
    }

    @Test
    void supports_requestWithoutContext_false() {
        when(req.getRequestContext()).thenReturn(null);
        assertFalse(provider.supports(req));
    }

    // ==================== session ====================

    @Test
    void exchange_sessionExists_bridgedFromHttpSession() {
        bindAdapter();
        HttpSession session = mock(HttpSession.class);
        when(servletRequest.getSession(false)).thenReturn(session);

        IWebExchange exchange = provider.createExchange(req, resp);

        IWebSession webSession = exchange.getSession();
        assertNotNull(webSession);
        assertTrue(webSession.exists());
    }

    @Test
    void exchange_noSession_returnsNull() {
        bindAdapter();
        when(servletRequest.getSession(false)).thenReturn(null);

        IWebExchange exchange = provider.createExchange(req, resp);

        assertNull(exchange.getSession(), "无会话时不应主动创建");
    }

    @Test
    void session_attributes_bridged() {
        bindAdapter();
        HttpSession session = mock(HttpSession.class);
        when(servletRequest.getSession(false)).thenReturn(session);
        when(session.getAttribute("user")).thenReturn("alice");
        // 每次返回新的 enumeration：HttpSession.getAttributeNames() 语义即每次调用独立遍历
        when(session.getAttributeNames())
                .thenAnswer(inv -> Collections.enumeration(Collections.singletonList("user")));

        IWebSession webSession = provider.createExchange(req, resp).getSession();

        assertTrue(webSession.containsAttribute("user"));
        assertEquals("alice", webSession.getAttributeValue("user"));
        assertEquals(1, webSession.getAttributeCount());
        assertTrue(webSession.getAllAttributeNames().contains("user"));
        assertEquals("alice", webSession.getAttributeMap().get("user"));

        webSession.setAttributeValue("k", "v");
        verify(session).setAttribute("k", "v");
        webSession.removeAttribute("k");
        verify(session).removeAttribute("k");
    }

    @Test
    void session_missingAttribute_notContained() {
        bindAdapter();
        HttpSession session = mock(HttpSession.class);
        when(servletRequest.getSession(false)).thenReturn(session);
        when(session.getAttribute("nope")).thenReturn(null);

        IWebSession webSession = provider.createExchange(req, resp).getSession();

        assertFalse(webSession.containsAttribute("nope"));
        assertNull(webSession.getAttributeValue("nope"));
    }

    // ==================== principal ====================

    @Test
    void exchange_principal_bridged() {
        bindAdapter();
        Principal principal = () -> "bob";
        when(servletRequest.getUserPrincipal()).thenReturn(principal);

        IWebExchange exchange = provider.createExchange(req, resp);

        assertNotNull(exchange.getPrincipal());
        assertEquals("bob", exchange.getPrincipal().getName());
    }

    @Test
    void exchange_noPrincipal_returnsNull() {
        bindAdapter();
        when(servletRequest.getUserPrincipal()).thenReturn(null);

        assertNull(provider.createExchange(req, resp).getPrincipal());
    }

    // ==================== cookies ====================

    @Test
    void request_cookies_bridged() {
        bindAdapter();
        when(servletRequest.getCookies()).thenReturn(new Cookie[]{
                new Cookie("a", "1"), new Cookie("b", "2")
        });

        org.thymeleaf.web.IWebRequest webRequest = provider.createExchange(req, resp).getRequest();

        assertTrue(webRequest.containsCookie("a"));
        assertEquals(2, webRequest.getCookieCount());
        assertTrue(webRequest.getAllCookieNames().contains("b"));
        assertArrayEquals(new String[]{"1"}, webRequest.getCookieValues("a"));
        assertArrayEquals(new String[]{"2"}, webRequest.getCookieMap().get("b"));
    }

    @Test
    void request_noCookies_emptyResults() {
        bindAdapter();
        when(servletRequest.getCookies()).thenReturn(null);

        org.thymeleaf.web.IWebRequest webRequest = provider.createExchange(req, resp).getRequest();

        assertFalse(webRequest.containsCookie("any"));
        assertEquals(0, webRequest.getCookieCount());
        assertTrue(webRequest.getAllCookieNames().isEmpty());
        assertEquals(0, webRequest.getCookieValues("any").length);
    }

    @Test
    void request_duplicateCookieNames_groupedIntoValues() {
        bindAdapter();
        when(servletRequest.getCookies()).thenReturn(new Cookie[]{
                new Cookie("x", "1"), new Cookie("x", "2")
        });

        org.thymeleaf.web.IWebRequest webRequest = provider.createExchange(req, resp).getRequest();

        assertEquals(1, webRequest.getCookieCount(), "同名 cookie 归并为一项");
        assertArrayEquals(new String[]{"1", "2"}, webRequest.getCookieValues("x"));
    }

    // ==================== server 元信息（servlet 精度） ====================

    @Test
    void request_serverPort_fromServletRequest() {
        bindAdapter();
        when(servletRequest.getServerPort()).thenReturn(8443);

        org.thymeleaf.web.IWebRequest webRequest = provider.createExchange(req, resp).getRequest();

        assertEquals(Integer.valueOf(8443), webRequest.getServerPort(),
                "servlet 场景应取真实端口而非 URI 推断的 80");
    }

    @Test
    void request_serverPort_nonPositive_fallsBackToSuper() {
        bindAdapter();
        when(servletRequest.getServerPort()).thenReturn(0);
        when(req.getURI()).thenReturn(java.net.URI.create("http://localhost:9090/app/x"));

        org.thymeleaf.web.IWebRequest webRequest = provider.createExchange(req, resp).getRequest();

        assertEquals(Integer.valueOf(9090), webRequest.getServerPort(), "非法端口回退基类 URI 推断");
    }

    @Test
    void request_serverName_fromServletRequest() {
        bindAdapter();
        when(servletRequest.getServerName()).thenReturn("example.com");

        org.thymeleaf.web.IWebRequest webRequest = provider.createExchange(req, resp).getRequest();

        assertEquals("example.com", webRequest.getServerName());
    }

    @Test
    void request_scheme_fromServletRequest() {
        bindAdapter();
        when(servletRequest.getScheme()).thenReturn("https");

        org.thymeleaf.web.IWebRequest webRequest = provider.createExchange(req, resp).getRequest();

        assertEquals("https", webRequest.getScheme());
    }
}
