package io.springperf.web.support.servlet;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.support.servlet.session.PerfHttpSession;
import io.springperf.web.support.servlet.session.PerfHttpSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PerfHttpServletRequestSessionIdTest {

    private PerfHttpServletRequest buildRequest(String cookieName, String cookieHeader,
                                                RequestContext requestContext, WebContext webContext) {
        WebServerHttpRequest webRequest = mock(WebServerHttpRequest.class);
        when(webRequest.getRequestContext()).thenReturn(requestContext);
        when(webRequest.getWebContext()).thenReturn(webContext);
        HttpHeaders headers = new HttpHeaders();
        if (cookieHeader != null) {
            headers.set(io.netty.handler.codec.http.HttpHeaders.Names.COOKIE, cookieHeader);
        }
        when(webRequest.getHeaders()).thenReturn(headers);
        return new PerfHttpServletRequest(webRequest);
    }

    // ========== 2-21：getRequestedSessionId 必须返回客户端提交的 id，而非本次请求新建的 id ==========

    @Test
    void getRequestedSessionId_returnsClientCookieId() {
        RequestContext requestContext = mock(RequestContext.class);
        WebContext webContext = mock(WebContext.class);
        PerfHttpSessionManager manager = mock(PerfHttpSessionManager.class);
        when(manager.getCookieName()).thenReturn("SESSIONID");
        when(webContext.getWebComponent(PerfHttpSessionManager.class)).thenReturn(manager);

        PerfHttpServletRequest req = buildRequest("SESSIONID", "SESSIONID=client-id; other=1",
                requestContext, webContext);
        assertEquals("client-id", req.getRequestedSessionId());
    }

    @Test
    void getRequestedSessionId_returnsClientIdEvenAfterSessionCreated() {
        RequestContext requestContext = mock(RequestContext.class);
        WebContext webContext = mock(WebContext.class);
        PerfHttpSessionManager manager = mock(PerfHttpSessionManager.class);
        when(manager.getCookieName()).thenReturn("SESSIONID");
        when(webContext.getWebComponent(PerfHttpSessionManager.class)).thenReturn(manager);

        // 本次请求已新建/绑定会话（如 getSession(true) 之后），缓存里是「新建 id」
        PerfHttpSession session = mock(PerfHttpSession.class);
        when(session.getId()).thenReturn("new-id");
        when(requestContext.getAttribute(PerfHttpSessionManager.SESSION_ATTR_KEY)).thenReturn(session);

        PerfHttpServletRequest req = buildRequest("SESSIONID", "SESSIONID=client-id",
                requestContext, webContext);
        // 仍应返回客户端提交的 id，符合 Servlet 规范（用于会话固定检测等语义），而非 new-id
        assertEquals("client-id", req.getRequestedSessionId());
    }

    @Test
    void getRequestedSessionId_noCookieFallsBackToCreatedSession() {
        RequestContext requestContext = mock(RequestContext.class);
        WebContext webContext = mock(WebContext.class);
        PerfHttpSessionManager manager = mock(PerfHttpSessionManager.class);
        when(manager.getCookieName()).thenReturn("SESSIONID");
        when(webContext.getWebComponent(PerfHttpSessionManager.class)).thenReturn(manager);

        // 无 cookie：回退到本次请求新建的会话 id（供 URL 重写编码 jsessionid）
        PerfHttpSession session = mock(PerfHttpSession.class);
        when(session.getId()).thenReturn("new-id");
        when(requestContext.getAttribute(PerfHttpSessionManager.SESSION_ATTR_KEY)).thenReturn(session);

        PerfHttpServletRequest req = buildRequest("SESSIONID", null, requestContext, webContext);
        assertEquals("new-id", req.getRequestedSessionId());
    }
}
