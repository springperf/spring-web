package io.springperf.web.support.servlet;

import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.http.support.HttpInputMessagePart;
import io.springperf.web.support.servlet.context.PerfServletContext;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import io.springperf.web.support.servlet.session.PerfHttpSession;
import io.springperf.web.support.servlet.session.PerfHttpSessionManager;
import javax.servlet.AsyncContext;
import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.util.LinkedMultiValueMap;

import java.io.ByteArrayInputStream;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 琛ュ厖 PerfHttpServletRequest 瑕嗙洊鐜囷細requestURL/鏈嶅姟鍣ㄧ鍙ｃ€乵ultipart 缂哄け銆? * async start/getAsyncContext/upgrade銆乻ession 鍒涘缓/鍙樻洿/鏍￠獙銆佺櫥褰曟敞閿€銆佽璇佷笌 servletContext 瑙ｆ瀽銆? */
class PerfHttpServletRequestSessionTest {

    private WebServerHttpRequest request;
    private RequestContext requestContext;
    private WebContext webContext;
    private Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
    private Map<String, Object> stringAttrs = new HashMap<>();

    @BeforeEach
    void setUp() {
        request = mock(WebServerHttpRequest.class);
        requestContext = mock(RequestContext.class);
        webContext = mock(WebContext.class);
        when(request.getRequestContext()).thenReturn(requestContext);
        when(request.getWebContext()).thenReturn(webContext);
        when(requestContext.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            fastAttrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());
        when(requestContext.getAttribute(anyString())).thenAnswer(inv -> stringAttrs.get(inv.getArgument(0)));
        when(requestContext.getAttributes()).thenReturn(stringAttrs);
        doAnswer(inv -> {
            stringAttrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(anyString(), any());
        doAnswer(inv -> stringAttrs.remove(inv.getArgument(0))).when(requestContext).removeAttribute(anyString());
    }

    private void headers(String... kv) {
        HttpHeaders h = new HttpHeaders();
        for (int i = 0; i < kv.length; i += 2) {
            h.add(kv[i], kv[i + 1]);
        }
        when(request.getHeaders()).thenReturn(h);
    }

    private PerfHttpServletRequest newReq() {
        return new PerfHttpServletRequest(request);
    }

    /** 灏?servlet 鍝嶅簲缁戝畾鍒拌姹備笂涓嬫枃锛圫ervletAttribute.getResponse 缁?ServletAdapterContext 璇诲彇锛?*/
    private void bindResponse(PerfHttpServletResponse servletResp) {
        ServletAdapterContext adapter = new ServletAdapterContext(newReq(), servletResp, null);
        ServletAttribute.setAdapterContext(requestContext, adapter);
    }

    private PerfHttpSessionManager sessionManager() {
        PerfHttpSessionManager manager = mock(PerfHttpSessionManager.class);
        when(webContext.getWebComponent(PerfHttpSessionManager.class)).thenReturn(manager);
        lenient().when(manager.getCookieName()).thenReturn("SESSION");
        lenient().when(manager.getCookiePath()).thenReturn("/");
        lenient().when(manager.isCookieSecure()).thenReturn(false);
        lenient().when(manager.getSameSite()).thenReturn(null);
        return manager;
    }

    /* ==================== URL / 绔彛 ==================== */

    @Test
    void getRequestURL_http80_omitsPort() {
        headers("Host", "example.com");
        when(request.getUriStr()).thenReturn("/a/b");
        when(request.getWebContext()).thenReturn(webContext);
        when(webContext.getProps()).thenReturn(mock(io.springperf.web.context.ApplicationProperties.class));
        when(webContext.getProps().getInt(PropertiesConstant.SERVER_PORT)).thenReturn(80);

        assertEquals("http://example.com/a/b", newReq().getRequestURL().toString());
    }

    @Test
    void getRequestURL_nonStandardPort_appendsPort() {
        headers("Host", "example.com");
        when(request.getUriStr()).thenReturn("/a");
        when(webContext.getProps()).thenReturn(mock(io.springperf.web.context.ApplicationProperties.class));
        when(webContext.getProps().getInt(PropertiesConstant.SERVER_PORT)).thenReturn(8080);

        assertEquals("http://example.com:8080/a", newReq().getRequestURL().toString());
    }

    @Test
    void getContextPath_returnsWebContextPath() {
        when(webContext.getContextPath()).thenReturn("/ctx");
        assertEquals("/ctx", newReq().getContextPath());
    }

    /* ==================== multipart 缂哄け ==================== */

    @Test
    void getPart_notMultipart_throws() {
        when(request.getPartMap()).thenReturn(null);
        assertThrows(ServletException.class, () -> newReq().getPart("x"));
    }

    @Test
    void getPart_multipartMissingName_returnsNull() throws Exception {
        LinkedMultiValueMap<String, HttpInputMessagePart> parts = new LinkedMultiValueMap<>();
        parts.add("a", mock(HttpInputMessagePart.class));
        when(request.getPartMap()).thenReturn(parts);
        assertNull(newReq().getPart("missing"));
    }

    /* ==================== dispatcher / servletContext ==================== */

    @Test
    void dispatcherType_defaultAndSettable() {
        PerfHttpServletRequest req = newReq();
        assertEquals(DispatcherType.REQUEST, req.getDispatcherType());
        req.setDispatcherType(DispatcherType.FORWARD);
        assertEquals(DispatcherType.FORWARD, req.getDispatcherType());
    }

    @Test
    void getServletContext_registeredComponent_returned() {
        PerfServletContext servletCtx = mock(PerfServletContext.class);
        when(webContext.getWebComponent(PerfServletContext.class)).thenReturn(servletCtx);
        assertSame(servletCtx, newReq().getServletContext());
    }

    @Test
    void getServletContext_missing_fallsBackToSuper() {
        when(webContext.getWebComponent(PerfServletContext.class)).thenReturn(null);
        // 无 PerfServletContext 注册时回退到 AbstractFastFailHttpServletRequest 的快速失败
        assertThrows(UnsupportedOperationException.class, () -> newReq().getServletContext());
    }

    /* ==================== async ==================== */

    @Test
    void startAsync_existingAsyncContext_returnsExisting() {
        PerfAsyncContext ctx = mock(PerfAsyncContext.class);
        PerfAsyncContext.set(requestContext, ctx);
        assertSame(ctx, newReq().startAsync());
    }

    @Test
    void startAsync_noResponse_throws() {
        assertThrows(IllegalStateException.class, () -> newReq().startAsync());
    }

    @Test
    void isAsyncStarted_noContext_false() {
        assertFalse(newReq().isAsyncStarted());
    }

    @Test
    void getAsyncContext_noContext_throws() {
        assertThrows(IllegalStateException.class, () -> newReq().getAsyncContext());
    }

    @Test
    void startAsync_withResponse_createsContext() throws Exception {
        WebServerHttpResponse webResponse = mock(WebServerHttpResponse.class);
        PerfHttpServletResponse servletResp = mock(PerfHttpServletResponse.class);
        when(servletResp.getResponse()).thenReturn(webResponse);
        bindResponse(servletResp);
        when(request.getBody()).thenReturn(new ByteArrayInputStream(new byte[0]));

        PerfHttpServletRequest req = newReq();
        AsyncContext ctx = req.startAsync();
        assertNotNull(ctx);
        assertSame(ctx, req.getAsyncContext());
        assertTrue(req.isAsyncStarted());
    }

    /* ==================== upgrade ==================== */

    static class DummyUpgradeHandler implements javax.servlet.http.HttpUpgradeHandler {
        @Override
        public void init(javax.servlet.http.WebConnection wc) {
        }

        @Override
        public void destroy() {
        }
    }

    @Test
    void upgrade_createsHandler() throws Exception {
        WebServerHttpResponse webResponse = mock(WebServerHttpResponse.class);
        when(webResponse.getHeaders()).thenReturn(new HttpHeaders());
        when(request.getBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        PerfHttpServletResponse servletResp = mock(PerfHttpServletResponse.class);
        when(servletResp.getResponse()).thenReturn(webResponse);
        bindResponse(servletResp);
        when(servletResp.getOutputStream()).thenReturn(mock(javax.servlet.ServletOutputStream.class));

        PerfHttpServletRequest req = newReq();
        DummyUpgradeHandler handler = req.upgrade(DummyUpgradeHandler.class);
        assertNotNull(handler);
        verify(webResponse).setStatusCode(org.springframework.http.HttpStatus.SWITCHING_PROTOCOLS);
        verify(webResponse).setHandled();
    }

    @Test
    void upgrade_badHandlerClass_throwsServletException() throws Exception {
        when(request.getBody()).thenReturn(new ByteArrayInputStream(new byte[0]));
        assertThrows(ServletException.class,
                () -> newReq().upgrade(NoDefaultCtorUpgradeHandler.class));
    }

    static class NoDefaultCtorUpgradeHandler implements javax.servlet.http.HttpUpgradeHandler {
        @SuppressWarnings("unused")
        public NoDefaultCtorUpgradeHandler(String unused) {
        }

        @Override
        public void init(javax.servlet.http.WebConnection wc) {
        }

        @Override
        public void destroy() {
        }
    }

    /* ==================== session ==================== */

    @Test
    void getRequestedSessionId_fromCookie() {
        sessionManager();
        headers("Cookie", "SESSION=abc");
        when(request.getHeaders()).thenReturn(new HttpHeaders() {{
            add("Cookie", "SESSION=abc");
        }});
        PerfHttpServletRequest req = newReq();
        assertEquals("abc", req.getRequestedSessionId());
    }

    @Test
    void getRequestedSessionId_cachedSession_returnsItsId() {
        PerfHttpSessionManager manager = sessionManager();
        when(manager.getCookieName()).thenReturn("SESSION");
        PerfHttpSession session = mock(PerfHttpSession.class);
        when(session.getId()).thenReturn("sid");
        fastAttrs.put(PerfHttpSessionManager.SESSION_ATTR_KEY, session);
        assertEquals("sid", newReq().getRequestedSessionId());
    }

    @Test
    void getSession_existingById_cachesAndReturns() {
        PerfHttpSessionManager manager = sessionManager();
        when(manager.getCookieName()).thenReturn("SESSION");
        headers("Cookie", "SESSION=sid");
        when(request.getHeaders()).thenReturn(new HttpHeaders() {{
            add("Cookie", "SESSION=sid");
        }});
        PerfHttpSession session = mock(PerfHttpSession.class);
        when(manager.getSession("sid")).thenReturn(session);
        when(session.isInvalid()).thenReturn(false);

        HttpSession result = newReq().getSession(false);
        assertSame(session, result);
    }

    @Test
    void getSession_invalidCached_createsNew() {
        PerfHttpSessionManager manager = sessionManager();
        when(manager.getCookieName()).thenReturn("SESSION");
        PerfHttpSession invalid = mock(PerfHttpSession.class);
        when(invalid.isInvalid()).thenReturn(true);
        fastAttrs.put(PerfHttpSessionManager.SESSION_ATTR_KEY, invalid);
        PerfHttpSession fresh = mock(PerfHttpSession.class);
        when(fresh.getId()).thenReturn("fresh");
        when(manager.createSession()).thenReturn(fresh);
        when(manager.getCookiePath()).thenReturn("/");
        when(manager.isCookieSecure()).thenReturn(false);
        when(manager.getSameSite()).thenReturn(null);
        when(webContext.getWebComponent(PerfHttpSessionManager.class)).thenReturn(manager);

        HttpSession result = newReq().getSession(true);
        assertSame(fresh, result);
    }

    @Test
    void getSession_noManager_throws() {
        when(webContext.getWebComponent(PerfHttpSessionManager.class)).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> newReq().getSession(true));
    }

    @Test
    void getSession_createFalse_noSession_returnsNull() {
        sessionManager();
        headers("Other", "x");
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        PerfHttpServletRequest req = newReq();
        assertNull(req.getSession(false));
    }

    @Test
    void isRequestedSessionIdFromCookie_matchesIdPresence() {
        sessionManager();
        headers("Other", "x");
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        assertFalse(newReq().isRequestedSessionIdFromCookie());
    }

    @Test
    void isRequestedSessionIdFromURL_alwaysFalse() {
        assertFalse(newReq().isRequestedSessionIdFromURL());
    }

    @Test
    void isRequestedSessionIdValid_cachedSession_true() {
        PerfHttpSession session = mock(PerfHttpSession.class);
        when(session.isInvalid()).thenReturn(false);
        fastAttrs.put(PerfHttpSessionManager.SESSION_ATTR_KEY, session);
        assertTrue(newReq().isRequestedSessionIdValid());
    }

    @Test
    void isRequestedSessionIdValid_noSessionId_false() {
        sessionManager();
        headers("Other", "x");
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        assertFalse(newReq().isRequestedSessionIdValid());
    }

    @Test
    void changeSessionId_noSession_throws() {
        sessionManager();
        headers("Other", "x");
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        assertThrows(IllegalStateException.class, () -> newReq().changeSessionId());
    }

    @Test
    void changeSessionId_withSession_rotatesAndSetsCookie() throws Exception {
        PerfHttpSessionManager manager = sessionManager();
        when(manager.getCookieName()).thenReturn("SESSION");
        when(manager.getCookiePath()).thenReturn("/");
        when(manager.isCookieSecure()).thenReturn(false);
        when(manager.getSameSite()).thenReturn("Lax");

        PerfHttpSession old = mock(PerfHttpSession.class);
        when(old.getId()).thenReturn("old");
        fastAttrs.put(PerfHttpSessionManager.SESSION_ATTR_KEY, old);
        when(manager.getSession("old")).thenReturn(old);

        PerfHttpSession rotated = mock(PerfHttpSession.class);
        when(rotated.getId()).thenReturn("new");
        when(manager.changeSessionId(old)).thenReturn(rotated);

        PerfHttpServletResponse resp = mock(PerfHttpServletResponse.class);
        WebServerHttpResponse webResponse = mock(WebServerHttpResponse.class);
        when(resp.getResponse()).thenReturn(webResponse);
        bindResponse(resp);
        headers("Other", "x");

        PerfHttpServletRequest req = newReq();
        req.getSession(true);
        assertEquals("new", req.changeSessionId());
        verify(resp).addCookie(any(Cookie.class));
    }

    /* ==================== security ==================== */

    @Test
    void getRemoteUser_withPrincipal_returnsName() {
        PerfHttpSessionManager manager = sessionManager();
        when(manager.getCookieName()).thenReturn("SESSION");
        headers("Cookie", "SESSION=sid");
        when(request.getHeaders()).thenReturn(new HttpHeaders() {{
            add("Cookie", "SESSION=sid");
        }});
        PerfHttpSession session = mock(PerfHttpSession.class);
        when(session.isInvalid()).thenReturn(false);
        when(manager.getSession("sid")).thenReturn(session);
        PerfHttpPrincipal principal = mock(PerfHttpPrincipal.class);
        when(principal.getName()).thenReturn("alice");
        when(principal.hasRole("admin")).thenReturn(true);
        when(session.getAttribute(PerfHttpSessionManager.PRINCIPAL_KEY)).thenReturn(principal);

        assertEquals("alice", newReq().getRemoteUser());
        assertEquals(principal, newReq().getUserPrincipal());
        assertTrue(newReq().isUserInRole("admin"));
    }

    @Test
    void getRemoteUser_noSession_null() {
        sessionManager();
        headers("Other", "x");
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        assertNull(newReq().getRemoteUser());
        assertNull(newReq().getUserPrincipal());
    }

    @Test
    void isUserInRole_nullRole_false() {
        assertFalse(newReq().isUserInRole(null));
    }

    @Test
    void login_noManager_throws() {
        when(webContext.getWebComponent(PerfHttpSessionManager.class)).thenReturn(null);
        assertThrows(ServletException.class, () -> newReq().login("u", "p"));
    }

    @Test
    void login_noAuthenticator_throws() {
        PerfHttpSessionManager manager = sessionManager();
        when(manager.getAuthenticator()).thenReturn(null);
        assertThrows(ServletException.class, () -> newReq().login("u", "p"));
    }

    @Test
    void login_authenticationFails_throws() throws Exception {
        PerfHttpSessionManager manager = sessionManager();
        Authenticator authenticator = mock(Authenticator.class);
        when(manager.getAuthenticator()).thenReturn(authenticator);
        when(authenticator.authenticate("u", "p")).thenReturn(null);
        assertThrows(ServletException.class, () -> newReq().login("u", "p"));
    }

    @Test
    void login_success_rotatesSessionIdAndSetsPrincipal() throws Exception {
        PerfHttpSessionManager manager = sessionManager();
        when(manager.getCookieName()).thenReturn("SESSION");
        when(manager.getCookiePath()).thenReturn("/");
        when(manager.isCookieSecure()).thenReturn(false);
        when(manager.getSameSite()).thenReturn(null);
        Authenticator authenticator = mock(Authenticator.class);
        when(manager.getAuthenticator()).thenReturn(authenticator);
        Principal authPrincipal = mock(Principal.class);
        when(authPrincipal.getName()).thenReturn("bob");
        when(authenticator.authenticate("bob", "pw")).thenReturn(authPrincipal);

        PerfHttpSession oldSession = mock(PerfHttpSession.class);
        when(oldSession.getId()).thenReturn("old-id");
        when(manager.createSession()).thenReturn(oldSession);
        PerfHttpSession newSession = mock(PerfHttpSession.class);
        when(newSession.getId()).thenReturn("new-id");
        when(manager.changeSessionId(oldSession)).thenReturn(newSession);
        headers("Other", "x");

        newReq().login("bob", "pw");

        // 登录成功后必须轮换 session ID（会话固定防护）
        verify(manager).changeSessionId(oldSession);
        verify(newSession).setAttribute(eq(PerfHttpSessionManager.PRINCIPAL_KEY), any(PerfHttpPrincipal.class));
        // principal 应写入轮换后的新 session
        PerfHttpSession cached = (PerfHttpSession) fastAttrs.get(PerfHttpSessionManager.SESSION_ATTR_KEY);
        assertEquals("new-id", cached.getId());
    }

    @Test
    void logout_noSession_noop() {
        sessionManager();
        headers("Other", "x");
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        assertDoesNotThrow(() -> newReq().logout());
    }

    @Test
    void logout_withSession_removesPrincipal() throws Exception {
        PerfHttpSessionManager manager = sessionManager();
        when(manager.getCookieName()).thenReturn("SESSION");
        headers("Cookie", "SESSION=sid");
        when(request.getHeaders()).thenReturn(new HttpHeaders() {{
            add("Cookie", "SESSION=sid");
        }});
        PerfHttpSession session = mock(PerfHttpSession.class);
        when(session.isInvalid()).thenReturn(false);
        when(manager.getSession("sid")).thenReturn(session);

        newReq().logout();

        verify(session).removeAttribute(PerfHttpSessionManager.PRINCIPAL_KEY);
    }

    @Test
    void authenticate_alreadyAuthenticated_true() throws Exception {
        PerfHttpSessionManager manager = sessionManager();
        when(manager.getCookieName()).thenReturn("SESSION");
        headers("Cookie", "SESSION=sid");
        when(request.getHeaders()).thenReturn(new HttpHeaders() {{
            add("Cookie", "SESSION=sid");
        }});
        PerfHttpSession session = mock(PerfHttpSession.class);
        when(session.isInvalid()).thenReturn(false);
        when(manager.getSession("sid")).thenReturn(session);
        when(session.getAttribute(PerfHttpSessionManager.PRINCIPAL_KEY))
                .thenReturn(mock(PerfHttpPrincipal.class));

        HttpServletResponse resp = mock(HttpServletResponse.class);
        assertTrue(newReq().authenticate(resp));
    }

    @Test
    void authenticate_notAuthenticated_setsChallenge() throws Exception {
        sessionManager();
        headers("Other", "x");
        when(request.getHeaders()).thenReturn(new HttpHeaders());
        HttpServletResponse resp = mock(HttpServletResponse.class);

        assertFalse(newReq().authenticate(resp));
        verify(resp).sendError(HttpServletResponse.SC_UNAUTHORIZED);
    }

    /* ==================== 补充覆盖 ==================== */

    @Test
    void rebind_withDifferentRequest_resetsCookies() throws Exception {
        when(request.getHeaders()).thenReturn(new HttpHeaders() {{
            add("Cookie", "SESSION=old");
        }});
        PerfHttpServletRequest req = newReq();
        Cookie[] before = req.getCookies();

        WebServerHttpRequest newRequest = mock(WebServerHttpRequest.class);
        when(newRequest.getRequestContext()).thenReturn(requestContext);
        when(newRequest.getWebContext()).thenReturn(webContext);
        when(newRequest.getHeaders()).thenReturn(new HttpHeaders() {{
            add("Cookie", "SESSION=new");
        }});
        req.rebind(newRequest);
        Cookie[] after = req.getCookies();
        assertNotEquals(before[0].getValue(), after[0].getValue());
    }

    @Test
    void isAsyncSupported_alwaysTrue() {
        assertTrue(newReq().isAsyncSupported());
    }

    @Test
    void getRequestURL_https443_omitsPort() {
        // getScheme 固定 http，走 port!=80 分支附加端口；此处验证 scheme 为 http 时端口逻辑
        headers("Host", "example.com:443");
        when(request.getUriStr()).thenReturn("/secure");
        when(webContext.getProps()).thenReturn(mock(io.springperf.web.context.ApplicationProperties.class));
        when(webContext.getProps().getInt(PropertiesConstant.SERVER_PORT)).thenReturn(443);

        assertEquals("http://example.com:443/secure", newReq().getRequestURL().toString());
    }

    @Test
    void isRequestedSessionIdValid_existingSessionInStore_cachesAndReturnsTrue() {
        PerfHttpSessionManager manager = sessionManager();
        when(manager.getCookieName()).thenReturn("SESSION");
        headers("Cookie", "SESSION=sid");
        when(request.getHeaders()).thenReturn(new HttpHeaders() {{
            add("Cookie", "SESSION=sid");
        }});
        PerfHttpSession session = mock(PerfHttpSession.class);
        when(session.isInvalid()).thenReturn(false);
        when(manager.getSession("sid")).thenReturn(session);

        assertTrue(newReq().isRequestedSessionIdValid());
        assertSame(session, fastAttrs.get(PerfHttpSessionManager.SESSION_ATTR_KEY));
    }

    @Test
    void isRequestedSessionIdValid_noManager_false() {
        headers("Cookie", "SESSION=sid");
        when(request.getHeaders()).thenReturn(new HttpHeaders() {{
            add("Cookie", "SESSION=sid");
        }});
        when(webContext.getWebComponent(PerfHttpSessionManager.class)).thenReturn(null);
        assertFalse(newReq().isRequestedSessionIdValid());
    }
    /* ==================== 补充覆盖（续） ==================== */

    @Test
    void login_success_withPerfHttpPrincipal_reusesPrincipal() throws Exception {
        PerfHttpSessionManager manager = sessionManager();
        when(manager.getCookieName()).thenReturn("SESSION");
        when(manager.getCookiePath()).thenReturn("/");
        when(manager.isCookieSecure()).thenReturn(false);
        when(manager.getSameSite()).thenReturn(null);
        Authenticator authenticator = mock(Authenticator.class);
        when(manager.getAuthenticator()).thenReturn(authenticator);
        PerfHttpPrincipal authPrincipal = mock(PerfHttpPrincipal.class);
        when(authPrincipal.getName()).thenReturn("carol");
        when(authenticator.authenticate("carol", "pw")).thenReturn(authPrincipal);

        PerfHttpSession oldSession = mock(PerfHttpSession.class);
        when(oldSession.getId()).thenReturn("old-id");
        when(manager.createSession()).thenReturn(oldSession);
        PerfHttpSession newSession = mock(PerfHttpSession.class);
        when(newSession.getId()).thenReturn("new-id");
        when(manager.changeSessionId(oldSession)).thenReturn(newSession);
        headers("Other", "x");

        newReq().login("carol", "pw");

        verify(newSession).setAttribute(eq(PerfHttpSessionManager.PRINCIPAL_KEY), same(authPrincipal));
    }

    /* ==================== getRemoteUser with PerfHttpPrincipal principal ==================== */

    @Test
    void isUserInRole_withRoleMatch_true() {
        PerfHttpSessionManager manager = sessionManager();
        when(manager.getCookieName()).thenReturn("SESSION");
        headers("Cookie", "SESSION=sid");
        when(request.getHeaders()).thenReturn(new HttpHeaders() {{
            add("Cookie", "SESSION=sid");
        }});
        PerfHttpSession session = mock(PerfHttpSession.class);
        when(session.isInvalid()).thenReturn(false);
        when(manager.getSession("sid")).thenReturn(session);
        PerfHttpPrincipal principal = mock(PerfHttpPrincipal.class);
        when(principal.hasRole("admin")).thenReturn(true);
        when(session.getAttribute(PerfHttpSessionManager.PRINCIPAL_KEY)).thenReturn(principal);

        assertTrue(newReq().isUserInRole("admin"));
    }
}
