package io.springperf.web.support.servlet;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.support.servlet.session.PerfHttpSession;
import io.springperf.web.support.servlet.session.PerfHttpSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * getRequestedSessionId 客户端 id 解析缓存的回归测试（H1）。
 */
class PerfHttpServletRequestSessionIdCacheTest {

    private WebServerHttpRequest webRequest(RequestContext ctx, WebContext webCtx, String cookieHeader) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getRequestContext()).thenReturn(ctx);
        when(req.getWebContext()).thenReturn(webCtx);
        HttpHeaders headers = new HttpHeaders();
        if (cookieHeader != null) {
            headers.set(io.netty.handler.codec.http.HttpHeaders.Names.COOKIE, cookieHeader);
        }
        when(req.getHeaders()).thenReturn(headers);
        return req;
    }

    @Test
    void repeatedCalls_resolveOnlyOnce() {
        RequestContext ctx = mock(RequestContext.class);
        WebContext webCtx = mock(WebContext.class);
        PerfHttpSessionManager manager = mock(PerfHttpSessionManager.class);
        when(manager.getCookieName()).thenReturn("JSESSIONID");
        when(webCtx.getWebComponent(PerfHttpSessionManager.class)).thenReturn(manager);

        PerfHttpServletRequest req = new PerfHttpServletRequest(webRequest(ctx, webCtx, "JSESSIONID=abc; other=1"));

        assertEquals("abc", req.getRequestedSessionId());
        assertEquals("abc", req.getRequestedSessionId());
        assertEquals("abc", req.getRequestedSessionId());

        // 解析涉及 getSessionManager()（内部 getWebComponent），缓存生效后只应发生一次
        verify(webCtx, times(1)).getWebComponent(PerfHttpSessionManager.class);
        assertTrueSourceFlags(req, true, false);
    }

    @Test
    void sourceFlagCalls_reuseCachedParse() {
        RequestContext ctx = mock(RequestContext.class);
        WebContext webCtx = mock(WebContext.class);
        PerfHttpSessionManager manager = mock(PerfHttpSessionManager.class);
        when(manager.getCookieName()).thenReturn("JSESSIONID");
        when(webCtx.getWebComponent(PerfHttpSessionManager.class)).thenReturn(manager);

        PerfHttpServletRequest req = new PerfHttpServletRequest(webRequest(ctx, webCtx, "JSESSIONID=abc"));

        // isRequestedSessionIdFromCookie/URL 反复调用不应重复触发解析
        req.isRequestedSessionIdFromCookie();
        req.isRequestedSessionIdFromURL();
        req.isRequestedSessionIdFromCookie();
        assertEquals("abc", req.getRequestedSessionId());
        verify(webCtx, times(1)).getWebComponent(PerfHttpSessionManager.class);
    }

    @Test
    void rebindInvalidatesCache() {
        RequestContext ctx = mock(RequestContext.class);
        WebContext webCtx = mock(WebContext.class);
        PerfHttpSessionManager manager = mock(PerfHttpSessionManager.class);
        when(manager.getCookieName()).thenReturn("JSESSIONID");
        when(webCtx.getWebComponent(PerfHttpSessionManager.class)).thenReturn(manager);

        WebServerHttpRequest first = webRequest(ctx, webCtx, "JSESSIONID=old-id");
        PerfHttpServletRequest req = new PerfHttpServletRequest(first);
        assertEquals("old-id", req.getRequestedSessionId());

        WebServerHttpRequest second = webRequest(ctx, webCtx, "JSESSIONID=new-id");
        req.rebind(second);
        assertEquals("new-id", req.getRequestedSessionId(), "rebind 后应重新解析新 delegate 的 cookie");
    }

    @Test
    void fallbackToCreatedSession_isDynamic_notCached() {
        // 无 cookie：首次调用时请求内还没有会话 → null；随后 getSession(true) 建立会话 → 应动态返回新 id。
        // 这是 H1 缓存的关键边界：只缓存客户端提交部分，不缓存 fallback。
        RequestContext ctx = mock(RequestContext.class);
        WebContext webCtx = mock(WebContext.class);
        PerfHttpSessionManager manager = mock(PerfHttpSessionManager.class);
        when(manager.getCookieName()).thenReturn("JSESSIONID");
        when(webCtx.getWebComponent(PerfHttpSessionManager.class)).thenReturn(manager);

        PerfHttpServletRequest req = new PerfHttpServletRequest(webRequest(ctx, webCtx, null));
        assertNull(req.getRequestedSessionId(), "无 cookie 且无会话时应返回 null");

        PerfHttpSession session = mock(PerfHttpSession.class);
        when(session.getId()).thenReturn("new-id");
        when(ctx.getAttribute(PerfHttpSessionManager.SESSION_ATTR_KEY)).thenReturn(session);
        assertEquals("new-id", req.getRequestedSessionId(), "请求内新建会话后应动态回退到新 id，不受缓存影响");
    }

    private static void assertTrueSourceFlags(PerfHttpServletRequest req, boolean fromCookie, boolean fromUrl) {
        assertEquals(fromCookie, req.isRequestedSessionIdFromCookie());
        assertEquals(fromUrl, req.isRequestedSessionIdFromURL());
    }
}
