package io.springperf.web.support.view;

import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.support.servlet.ServletAttribute;
import io.springperf.web.support.servlet.context.ServletAdapterContext;
import io.springperf.web.view.WebExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.Ordered;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ServletWebExchangeProvider} 与 {@link ServletWebExchange}：Servlet 对象向模板渲染层的桥接。
 *
 * <p>薄接口形态（2.7.x）：exchange 只暴露 request / response / session / ServletContext，
 * 真实 <code>HttpSession</code> 直接下传，不再经 Thymeleaf 3.1 的 <code>IWebSession</code> 包装。</p>
 */
@ExtendWith(MockitoExtension.class)
class ServletWebExchangeProviderTest {

    @Mock WebServerHttpRequest req;
    @Mock WebServerHttpResponse resp;
    @Mock RequestContext requestContext;
    @Mock ServletAdapterContext adapterContext;
    @Mock HttpServletRequest servletRequest;
    @Mock HttpServletResponse servletResponse;

    private final Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
    private ServletWebExchangeProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ServletWebExchangeProvider();
    }

    /** 让 {@link ServletAttribute#getAdapterContext(WebServerHttpRequest, WebServerHttpResponse)} 命中已缓存的适配上下文。 */
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
        lenient().when(adapterContext.getResponse()).thenReturn(servletResponse);
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

    // ==================== exchange 桥接 ====================

    @Test
    void createExchange_delegatesServletObjects() {
        bindAdapter();

        WebExchange exchange = provider.createExchange(req, resp);

        assertSame(servletRequest, exchange.getRequest());
        assertSame(servletResponse, exchange.getResponse());
    }

    @Test
    void createExchange_sessionExists_returnsRealHttpSession() {
        bindAdapter();
        HttpSession session = mock(HttpSession.class);
        when(servletRequest.getSession(false)).thenReturn(session);

        assertSame(session, provider.createExchange(req, resp).getSession(),
                "应直接下传真实 HttpSession");
    }

    @Test
    void createExchange_noSession_returnsNull() {
        bindAdapter();
        when(servletRequest.getSession(false)).thenReturn(null);

        assertNull(provider.createExchange(req, resp).getSession(), "无会话时不应主动创建");
    }

    @Test
    void createExchange_servletContext_fromServletRequest() {
        bindAdapter();
        ServletContext servletContext = mock(ServletContext.class);
        when(servletRequest.getServletContext()).thenReturn(servletContext);

        assertSame(servletContext, provider.createExchange(req, resp).getServletContext());
    }

    @Test
    void exchange_exposesNativeRequestAndResponse() {
        bindAdapter();

        ServletWebExchange exchange = (ServletWebExchange) provider.createExchange(req, resp);

        assertSame(req, exchange.getNativeRequest());
        assertSame(resp, exchange.getNativeResponse());
    }
}
