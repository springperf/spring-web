package io.springperf.web.view.thymeleaf;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.WebExchange;
import io.springperf.web.view.WebExchangeProvider;
import org.junit.jupiter.api.Test;
import org.thymeleaf.context.IContext;
import org.thymeleaf.context.IWebContext;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThymeleafWebContextTest {

    private final WebServerHttpRequest req = mock(WebServerHttpRequest.class);
    private final WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

    @Test
    void implementsThymeleaf30WebContext() {
        ThymeleafWebContext ctx = new ThymeleafWebContext(new HashMap<>(), Locale.US, req, resp);
        assertTrue(ctx instanceof IWebContext, "应实现 Thymeleaf 3.0 的 web 版 IWebContext");
        assertTrue(ctx instanceof IContext, "IWebContext 扩展自 IContext");
    }

    @Test
    void getLocale_usesProvidedLocale() {
        ThymeleafWebContext ctx = new ThymeleafWebContext(new HashMap<>(), Locale.US, req, resp);
        assertEquals(Locale.US, ctx.getLocale());
    }

    @Test
    void getLocale_defaultsWhenNull() {
        ThymeleafWebContext ctx = new ThymeleafWebContext(new HashMap<>(), null, req, resp);
        assertEquals(Locale.getDefault(), ctx.getLocale());
    }

    @Test
    void variables_modelBased() {
        Map<String, Object> model = new HashMap<>();
        model.put("name", "Perf");
        ThymeleafWebContext ctx = new ThymeleafWebContext(model, Locale.US, req, resp);
        assertTrue(ctx.containsVariable("name"));
        assertFalse(ctx.containsVariable("missing"));
        assertEquals("Perf", ctx.getVariable("name"));
        assertTrue(ctx.getVariableNames().contains("name"));
        assertEquals(1, ctx.getVariableNames().size());
    }

    @Test
    void emptyModel_returnsNoVariables() {
        ThymeleafWebContext ctx = new ThymeleafWebContext(null, Locale.US, req, resp);
        assertFalse(ctx.containsVariable("anything"));
        assertNull(ctx.getVariable("anything"));
        assertTrue(ctx.getVariableNames().isEmpty());
    }

    @Test
    void webContext_delegatesToProvidedExchange() {
        HttpServletRequest servletRequest = mock(HttpServletRequest.class);
        HttpServletResponse servletResponse = mock(HttpServletResponse.class);
        HttpSession session = mock(HttpSession.class);
        ServletContext servletContext = mock(ServletContext.class);
        ThymeleafWebContext ctx = new ThymeleafWebContext(new HashMap<>(), Locale.US, req, resp,
                fixedProvider(servletRequest, servletResponse, session, servletContext));

        assertSame(servletRequest, ctx.getRequest());
        assertSame(servletResponse, ctx.getResponse());
        assertSame(session, ctx.getSession());
        assertSame(servletContext, ctx.getServletContext());
    }

    @Test
    void webContext_defaultProviderYieldsNullServletObjects() {
        ThymeleafWebContext ctx = new ThymeleafWebContext(new HashMap<>(), Locale.US, req, resp);

        assertNull(ctx.getRequest(), "原生场景无 servlet request");
        assertNull(ctx.getResponse(), "原生场景无 servlet response");
        assertNull(ctx.getSession(), "原生场景无 session");
        assertNull(ctx.getServletContext(), "原生场景无 ServletContext");
    }

    @Test
    void webContext_nullProviderFallsBackToDefault() {
        ThymeleafWebContext ctx = new ThymeleafWebContext(new HashMap<>(), Locale.US, req, resp, null);

        assertNull(ctx.getRequest(), "provider 为 null 时应退回默认实现而非 NPE");
    }

    @Test
    void sessionAttributes_injectedAsVariables() {
        // 模板不应依赖 #session 表达式对象（master 基线 Thymeleaf 3.1 已移除），会话属性统一以上下文变量提供
        HttpSession session = mock(HttpSession.class);
        when(session.getAttributeNames()).thenReturn(Collections.enumeration(Collections.singleton("user")));
        when(session.getAttribute("user")).thenReturn("alice");

        ThymeleafWebContext ctx = new ThymeleafWebContext(new HashMap<>(), Locale.US, req, resp,
                fixedProvider(null, null, session, null));

        assertTrue(ctx.containsVariable("user"));
        assertEquals("alice", ctx.getVariable("user"));
        assertTrue(ctx.getVariableNames().contains("user"));
    }

    @Test
    void modelOverridesSessionAttribute() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttributeNames()).thenReturn(Collections.enumeration(Collections.singleton("user")));
        when(session.getAttribute("user")).thenReturn("alice");
        Map<String, Object> model = new HashMap<>();
        model.put("user", "fromModel");

        ThymeleafWebContext ctx = new ThymeleafWebContext(model, Locale.US, req, resp,
                fixedProvider(null, null, session, null));

        assertEquals("fromModel", ctx.getVariable("user"), "model 应优先于 session 同名属性");
    }

    @Test
    void noSession_modelOnly() {
        // 默认 provider / 匿名访问：getSession(false) 为 null，仅暴露 model 变量
        Map<String, Object> model = new HashMap<>();
        model.put("only", "m");

        ThymeleafWebContext ctx = new ThymeleafWebContext(model, Locale.US, req, resp,
                fixedProvider(null, null, null, null));

        assertEquals("m", ctx.getVariable("only"));
        assertFalse(ctx.containsVariable("user"));
    }

    @Test
    void emptySession_keepsModelVariables() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttributeNames()).thenReturn(Collections.enumeration(Collections.<String>emptyList()));
        Map<String, Object> model = new HashMap<>();
        model.put("only", "m");

        ThymeleafWebContext ctx = new ThymeleafWebContext(model, Locale.US, req, resp,
                fixedProvider(null, null, session, null));

        assertEquals("m", ctx.getVariable("only"));
        assertEquals(1, ctx.getVariableNames().size());
    }

    /** 固定返回给定 servlet 对象的 provider，用于断言上下文确实走 SPI 取值。 */
    private static WebExchangeProvider fixedProvider(HttpServletRequest servletRequest,
                                                     HttpServletResponse servletResponse,
                                                     HttpSession session,
                                                     ServletContext servletContext) {
        return new WebExchangeProvider() {
            @Override
            public boolean supports(WebServerHttpRequest request) {
                return true;
            }

            @Override
            public WebExchange createExchange(WebServerHttpRequest request, WebServerHttpResponse response) {
                return new WebExchange() {
                    @Override
                    public HttpServletRequest getRequest() {
                        return servletRequest;
                    }

                    @Override
                    public HttpServletResponse getResponse() {
                        return servletResponse;
                    }

                    @Override
                    public HttpSession getSession() {
                        return session;
                    }

                    @Override
                    public ServletContext getServletContext() {
                        return servletContext;
                    }
                };
            }
        };
    }
}
