package io.springperf.web.view;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.beetl.BeetlViewResolver;
import io.springperf.web.view.freemarker.FreemarkerViewResolver;
import io.springperf.web.view.thymeleaf.ThymeleafViewResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.thymeleaf.web.IWebExchange;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * {@link WebExchangeProvider} 契约与默认实现。
 * <p>
 * 「按 order 选择首个 supports 的 provider」这一选择逻辑属于 {@code ThymeleafViewResolver.selectProvider}，
 * 由 {@code ThymeleafViewResolverTest} 直接调用真实方法覆盖；此处不再复刻一份选择算法，
 * 避免测试只验证测试自己的代码而从未进入产品分支。
 * </p>
 */
class WebExchangeProviderTest {

    private WebServerHttpRequest req;
    private WebServerHttpResponse resp;
    private WebContext wc;

    @BeforeEach
    void setUp() {
        req = mock(WebServerHttpRequest.class);
        resp = mock(WebServerHttpResponse.class);
        wc = mock(WebContext.class);
        org.mockito.Mockito.when(req.getWebContext()).thenReturn(wc);
    }

    @Test
    void defaultProvider_supportsAnyRequest() {
        assertTrue(new DefaultWebExchangeProvider().supports(req));
    }

    @Test
    void defaultProvider_hasLowestPrecedence() {
        assertEquals(Ordered.LOWEST_PRECEDENCE, new DefaultWebExchangeProvider().getOrder());
    }

    @Test
    void defaultProvider_createsExchangeWithNullSessionAndPrincipal() {
        IWebExchange exchange = new DefaultWebExchangeProvider().createExchange(req, resp);
        assertNotNull(exchange);
        assertNotNull(exchange.getRequest());
        assertNotNull(exchange.getApplication());
        assertNull(exchange.getSession(), "原生场景无 session 概念");
        assertNull(exchange.getPrincipal(), "原生场景无 principal");
    }

    @Test
    void builtInViewResolvers_startEnabled() {
        // 用真实实现类验证「默认启用」契约，而不是拿 lambda 去断言接口 default 方法自己返回 true
        assertTrue(new ThymeleafViewResolver().isEnabled());
        assertTrue(new FreemarkerViewResolver().isEnabled());
        assertTrue(new BeetlViewResolver().isEnabled());
    }
}
