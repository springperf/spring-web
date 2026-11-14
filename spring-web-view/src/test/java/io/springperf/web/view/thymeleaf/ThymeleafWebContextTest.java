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
    void modelNullValue_overridesSessionAttribute() {
        Map<String, Object> model = new HashMap<>();
        model.put("user", null);

        ThymeleafWebContext ctx = new ThymeleafWebContext(model, Locale.US, req, resp, sessionProvider());

        assertNull(ctx.getVariable("user"), "model 显式置 null 时不得回退到 session 值");
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
    void noSession_variableNamesReadsModelWithoutCopy() {
        // 回归：无 session 时每请求 new HashMap<>(model) 是渲染热路径上的无谓分配。
        // 直接返回 model.keySet() 才能证明没有复制；model 后续变化也应立即可见。
        Map<String, Object> model = new HashMap<>();
        model.put("a", "1");
        ThymeleafWebContext ctx = new ThymeleafWebContext(model, Locale.US, req, resp);

        assertSame(model.keySet(), ctx.getVariableNames(), "无 session 时变量名集合必须是 model 的只读视图，不能复制");
        model.put("b", "2");
        assertTrue(ctx.containsVariable("b"));
        assertEquals("2", ctx.getVariable("b"));
    }

    @Test
    void sessionAndModel_variableNamesContainBoth() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttributeNames()).thenReturn(Collections.enumeration(Collections.singletonList("user")));
        when(session.getAttribute("user")).thenReturn("alice");
        ThymeleafWebContext ctx = new ThymeleafWebContext(new HashMap<String, Object>(), Locale.US, req, resp,
                fixedProvider(null, null, session, null));

        assertTrue(ctx.getVariableNames().contains("user"), "session 属性应出现在变量名集合中");
        assertEquals("alice", ctx.getVariable("user"));
    }

    /** 构造一个提供 user=alice 会话的 provider（2.7.x 走 javax.servlet HttpSession）。 */
    private static WebExchangeProvider sessionProvider() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttributeNames()).thenReturn(Collections.enumeration(Collections.singletonList("user")));
        when(session.getAttribute("user")).thenReturn("alice");
        return fixedProvider(null, null, session, null);
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
