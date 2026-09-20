package io.springperf.web.view;

import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.thymeleaf.web.IWebExchange;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * {@link WebExchangeProvider} 选择机制与默认实现。
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
    void customProvider_takesPrecedenceByOrder() {
        // 模拟 servlet 场景：order 更小的 provider 优先命中
        RecordingProvider high = new RecordingProvider(Ordered.HIGHEST_PRECEDENCE);
        DefaultWebExchangeProvider low = new DefaultWebExchangeProvider();

        List<WebExchangeProvider> ordered = new ArrayList<>();
        ordered.add(high);
        ordered.add(low);

        WebExchangeProvider selected = select(ordered, req);
        assertSame(high, selected, "应选择 order 最小的支持者");
    }

    @Test
    void customProvider_notSupporting_fallsBackToDefault() {
        RecordingProvider unsupporting = new RecordingProvider(Ordered.HIGHEST_PRECEDENCE);
        unsupporting.supported = false;
        DefaultWebExchangeProvider low = new DefaultWebExchangeProvider();

        List<WebExchangeProvider> ordered = new ArrayList<>();
        ordered.add(unsupporting);
        ordered.add(low);

        WebExchangeProvider selected = select(ordered, req);
        assertSame(low, selected, "不支持时应回退到兜底 provider");
    }

    /** 与 ThymeleafViewResolver.selectProvider 相同的选择语义。 */
    private static WebExchangeProvider select(List<WebExchangeProvider> providers, WebServerHttpRequest request) {
        for (WebExchangeProvider provider : providers) {
            if (provider.supports(request)) {
                return provider;
            }
        }
        return new DefaultWebExchangeProvider();
    }

    private static class RecordingProvider implements WebExchangeProvider {
        private final int order;
        boolean supported = true;

        RecordingProvider(int order) {
            this.order = order;
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public boolean supports(WebServerHttpRequest request) {
            return supported;
        }

        @Override
        public IWebExchange createExchange(WebServerHttpRequest request, WebServerHttpResponse response) {
            return new DefaultWebExchangeProvider().createExchange(request, response);
        }
    }
}
