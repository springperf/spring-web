package io.springperf.web.core;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.ArgumentResolverRegistry;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.exception.ExceptionRegistry;
import io.springperf.web.core.filter.WebFilterRegistry;
import io.springperf.web.core.interceptor.InterceptorRegistry;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code spring.mvc.dispatch.error/options/trace} 闸门语义（{@link DispatcherHandler#isMethodDispatchEnabled}）。
 *
 * <p>锁定「方法 token 大小写不敏感」（Netty 对未知方法原样保留原始串，小写 {@code trace} 不得绕过
 * {@code dispatch.trace=false}）与「常见方法零扫描放行」的两条契约 —— 后者是 JFR 驱动的热路径改造
 * （原实现每请求对方法名做 {@code toUpperCase(Locale.ROOT)} 整串扫描 + Set 查表，
 * 叶帧占 59 样本 / 2195 ≈ 2.7%）。</p>
 */
class DispatcherHandlerMethodDispatchGateTest {

    private DispatcherHandler buildHandler(boolean error, boolean trace, boolean options) throws Exception {
        DispatcherHandler handler = new DispatcherHandler();
        WebContext webContext = mock(WebContext.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(webContext.getProps()).thenReturn(props);
        lenient().when(props.getBoolean(any(), anyBoolean())).thenReturn(true);
        lenient().when(props.getBoolean(eq(PropertiesConstant.MVC_DISPATCH_ERROR), anyBoolean())).thenReturn(error);
        lenient().when(props.getBoolean(eq(PropertiesConstant.MVC_DISPATCH_TRACE), anyBoolean())).thenReturn(trace);
        lenient().when(props.getBoolean(eq(PropertiesConstant.MVC_DISPATCH_OPTIONS), anyBoolean())).thenReturn(options);

        when(webContext.getWebComponentWithDefault(eq(MappingRegistry.class), any(MappingRegistry.class)))
                .thenReturn(mock(MappingRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(ExceptionRegistry.class), any(ExceptionRegistry.class)))
                .thenReturn(mock(ExceptionRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(ArgumentResolverRegistry.class), any(ArgumentResolverRegistry.class)))
                .thenReturn(mock(ArgumentResolverRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(ReturnValueResolverRegistry.class), any(ReturnValueResolverRegistry.class)))
                .thenReturn(mock(ReturnValueResolverRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(CorsRegistry.class), any(CorsRegistry.class)))
                .thenReturn(mock(CorsRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(InterceptorRegistry.class), any(InterceptorRegistry.class)))
                .thenReturn(mock(InterceptorRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(BizPoolRegistry.class), any(BizPoolRegistry.class)))
                .thenReturn(mock(BizPoolRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(AsyncSupportRegistry.class), any(AsyncSupportRegistry.class)))
                .thenReturn(mock(AsyncSupportRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(WebFilterRegistry.class), any(WebFilterRegistry.class)))
                .thenReturn(mock(WebFilterRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(WebMetrics.class), any())).thenReturn(mock(WebMetrics.class));

        handler.initWithWebContext(webContext);
        return handler;
    }

    /** 常见方法（长度既不等于闸门名）永远放行：GET/POST/PUT/HEAD/DELETE/PATCH/CONNECT。 */
    @Test
    void commonMethodsAlwaysPass() throws Exception {
        DispatcherHandler handler = buildHandler(false, false, false);
        for (String method : new String[]{"GET", "POST", "PUT", "HEAD", "DELETE", "PATCH", "CONNECT"}) {
            assertTrue(handler.isMethodDispatchEnabled(request(method, null)),
                    method + " 非闸门方法，应始终放行");
        }
    }

    /** 闸门方法大小写不敏感：小写/混合大小写不得绕过 dispatch.*=false。 */
    @Test
    void gatedMethodsAreCaseInsensitive() throws Exception {
        DispatcherHandler handler = buildHandler(false, false, false);
        for (String method : new String[]{"TRACE", "trace", "TrAcE"}) {
            assertFalse(handler.isMethodDispatchEnabled(request(method, null)),
                    method + " 应被 dispatch.trace=false 拦截");
        }
        for (String method : new String[]{"ERROR", "error", "ErRoR"}) {
            assertFalse(handler.isMethodDispatchEnabled(request(method, null)),
                    method + " 应被 dispatch.error=false 拦截");
        }
        assertFalse(handler.isMethodDispatchEnabled(request("options", null)),
                "options 应被 dispatch.options=false 拦截");
    }

    /** 开关为 true 时闸门方法正常分发。 */
    @Test
    void gatedMethodsPassWhenEnabled() throws Exception {
        DispatcherHandler handler = buildHandler(true, true, true);
        assertTrue(handler.isMethodDispatchEnabled(request("TRACE", null)));
        assertTrue(handler.isMethodDispatchEnabled(request("ERROR", null)));
        assertTrue(handler.isMethodDispatchEnabled(request("OPTIONS", null)));
    }

    /** OPTIONS 关闭时 CORS 预检仍需处理（框架级能力，非 @RequestMapping）。 */
    @Test
    void optionsDisabled_stillAllowsCorsPreflight() throws Exception {
        DispatcherHandler handler = buildHandler(true, true, false);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Access-Control-Request-Method", "GET");
        headers.setOrigin("http://example.com");

        assertTrue(handler.isMethodDispatchEnabled(request("OPTIONS", headers)),
                "CORS 预检请求应放行至 handleWithNoFullMatch 的预检分支");
    }

    @Test
    void nullMethodValue_passes() throws Exception {
        DispatcherHandler handler = buildHandler(false, false, false);
        assertTrue(handler.isMethodDispatchEnabled(request(null, null)));
    }

    private static WebServerHttpRequest request(String methodValue, HttpHeaders headers) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        lenient().when(req.getMethodValue()).thenReturn(methodValue);
        lenient().when(req.getHeaders()).thenReturn(headers == null ? new HttpHeaders() : headers);
        if (methodValue != null && methodValue.equalsIgnoreCase("OPTIONS")) {
            lenient().when(req.getMethod()).thenReturn(HttpMethod.OPTIONS);
        }
        return req;
    }
}
