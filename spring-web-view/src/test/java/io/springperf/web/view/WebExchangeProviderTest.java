package io.springperf.web.view;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link WebExchangeProvider} 选择机制与默认实现（薄接口形态）。
 *
 * <p>2.7.x 无 Thymeleaf 3.1 的 {@code IWebExchange}，故 exchange 为框架自有薄接口
 * {@link WebExchange}：仅暴露 request / response / session / ServletContext 四个方法。</p>
 */
class WebExchangeProviderTest {

    private final WebServerHttpRequest req = mock(WebServerHttpRequest.class);
    private final WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

    @Test
    void defaultProvider_supportsAnyRequest() {
        assertTrue(new DefaultWebExchangeProvider().supports(req));
    }

    @Test
    void defaultProvider_hasLowestPrecedence() {
        assertEquals(Ordered.LOWEST_PRECEDENCE, new DefaultWebExchangeProvider().getOrder());
    }

    @Test
    void defaultProvider_createsEmptyExchangeWithNullServletObjects() {
        WebExchange exchange = new DefaultWebExchangeProvider().createExchange(req, resp);

        assertNotNull(exchange);
        assertNull(exchange.getRequest(), "原生场景无 servlet request");
        assertNull(exchange.getResponse(), "原生场景无 servlet response");
        assertNull(exchange.getSession(), "原生场景无 session");
        assertNull(exchange.getServletContext(), "原生场景无 ServletContext");
    }

    @Test
    void defaultProvider_reusesStatelessEmptyExchange() {
        DefaultWebExchangeProvider provider = new DefaultWebExchangeProvider();

        // 空实现无状态且线程安全，复用同一实例以避免每次渲染分配
        assertSame(provider.createExchange(req, resp), provider.createExchange(req, resp));
    }

    @Test
    void customProvider_takesPrecedenceByOrder() {
        // 模拟 servlet 场景：order 更小的 provider 优先命中
        RecordingProvider high = new RecordingProvider(Ordered.HIGHEST_PRECEDENCE);
        DefaultWebExchangeProvider low = new DefaultWebExchangeProvider();

        WebExchangeProvider selected = select(Arrays.asList(high, low), req);

        assertSame(high, selected, "应选择 order 最小的支持者");
    }

    @Test
    void customProvider_notSupporting_fallsBackToDefault() {
        RecordingProvider unsupporting = new RecordingProvider(Ordered.HIGHEST_PRECEDENCE);
        unsupporting.supported = false;

        WebExchangeProvider selected = select(Collections.singletonList(unsupporting), req);

        assertTrue(selected instanceof DefaultWebExchangeProvider, "无支持者时应回退到兜底 provider");
    }

    @Test
    void selectedProvider_exchangeIsDelegatedVerbatim() {
        WebExchange expected = new DefaultWebExchangeProvider().createExchange(req, resp);
        WebExchangeProvider provider = new WebExchangeProvider() {
            @Override
            public boolean supports(WebServerHttpRequest request) {
                return true;
            }

            @Override
            public WebExchange createExchange(WebServerHttpRequest request, WebServerHttpResponse response) {
                return expected;
            }
        };

        WebExchangeProvider selected = select(Collections.singletonList(provider), req);

        assertSame(expected, selected.createExchange(req, resp), "消费方应原样使用 provider 返回的 exchange");
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
        public WebExchange createExchange(WebServerHttpRequest request, WebServerHttpResponse response) {
            return new DefaultWebExchangeProvider().createExchange(request, response);
        }
    }
}
