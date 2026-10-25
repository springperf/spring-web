package io.springperf.web.view.thymeleaf;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ThymeleafViewResolverTest {

    private ApplicationProperties props;
    private WebContext webContext;

    @BeforeEach
    void setUp() {
        props = mock(ApplicationProperties.class);
        when(props.get(ViewProperties.THYMELEAF_PREFIX, ViewProperties.THYMELEAF_PREFIX_DEFAULT))
                .thenReturn("templates/");
        when(props.get(ViewProperties.THYMELEAF_SUFFIX, ViewProperties.THYMELEAF_SUFFIX_DEFAULT)).thenReturn(".html");
        when(props.getBoolean(ViewProperties.THYMELEAF_CACHE, ViewProperties.THYMELEAF_CACHE_DEFAULT)).thenReturn(true);
        when(props.getBoolean(ViewProperties.THYMELEAF_ENABLED, ViewProperties.THYMELEAF_ENABLED_DEFAULT))
                .thenReturn(true);
        when(props.get(ViewProperties.THYMELEAF_ENCODING, ViewProperties.THYMELEAF_ENCODING_DEFAULT))
                .thenReturn("UTF-8");
        webContext = mock(WebContext.class);
        when(webContext.getProps()).thenReturn(props);
    }

    private ThymeleafViewResolver buildResolver() {
        ThymeleafViewResolver resolver = new ThymeleafViewResolver();
        resolver.initWithWebContext(webContext);
        return resolver;
    }

    @Test
    void resolveViewName_existingTemplate_returnsView() throws Exception {
        ThymeleafViewResolver resolver = buildResolver();
        View view = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertNotNull(view);
    }

    @Test
    void resolveViewName_missingTemplate_returnsNull() throws Exception {
        ThymeleafViewResolver resolver = buildResolver();
        assertNull(resolver.resolveViewName("does-not-exist", Locale.US, mock(WebServerHttpRequest.class)));
    }

    @Test
    void viewRender_rendersTemplateWithModel() throws Exception {
        ThymeleafViewResolver resolver = buildResolver();
        View view = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getLocale()).thenReturn(Locale.US);
        WebContext wc = mock(WebContext.class);
        when(wc.getContextPath()).thenReturn("");
        when(req.getWebContext()).thenReturn(wc);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(resp.getBody()).thenReturn(body);

        Map<String, Object> model = new HashMap<>();
        model.put("name", "Perf");
        view.render(model, req, resp);

        String output = new String(body.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(output.contains("Hello"), "渲染输出应包含模板内容: " + output);
        assertTrue(output.contains("Perf"), "渲染输出应包含 model 值: " + output);
    }

    @Test
    void initWithWebContext_registersDefaultProviderWhenAbsent() {
        ThymeleafViewResolver resolver = new ThymeleafViewResolver();
        resolver.initWithWebContext(webContext);

        // getWebComponentWithDefault 被调用于注册默认 provider
        verify(webContext).getWebComponentWithDefault(eq(io.springperf.web.view.WebExchangeProvider.class),
                any(io.springperf.web.view.DefaultWebExchangeProvider.class));
        // 随后取全量 provider 列表
        verify(webContext).getWebComponents(io.springperf.web.view.WebExchangeProvider.class);
    }

    @Test
    void viewRender_usesCustomProviderFromContainer() throws Exception {
        // 容器中存在自定义 provider（如 Servlet 场景）时应被选中
        io.springperf.web.view.WebExchangeProvider custom = mock(io.springperf.web.view.WebExchangeProvider.class);
        when(custom.supports(any(WebServerHttpRequest.class))).thenReturn(true);
        when(custom.createExchange(any(WebServerHttpRequest.class), any(WebServerHttpResponse.class)))
                .thenAnswer(inv -> new io.springperf.web.view.DefaultWebExchangeProvider()
                        .createExchange(inv.getArgument(0), inv.getArgument(1)));
        when(webContext.getWebComponents(io.springperf.web.view.WebExchangeProvider.class))
                .thenReturn(java.util.Collections.singletonList(custom));

        ThymeleafViewResolver resolver = buildResolver();
        View view = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getLocale()).thenReturn(Locale.US);
        WebContext wc = mock(WebContext.class);
        when(wc.getContextPath()).thenReturn("");
        when(req.getWebContext()).thenReturn(wc);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        when(resp.getBody()).thenReturn(new ByteArrayOutputStream());

        view.render(new HashMap<>(), req, resp);

        verify(custom).createExchange(req, resp);
    }

    @Test
    void resolveViewName_cached_returnsSameViewInstance() throws Exception {
        ThymeleafViewResolver resolver = buildResolver();
        View first = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        View second = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertSame(first, second, "模板缓存开启时同一视图名应复用同一 View 实例");
    }

    @Test
    void resolveViewName_cacheDisabled_returnsFreshView() throws Exception {
        when(props.get(ViewProperties.THYMELEAF_CACHE, null)).thenReturn("false");
        ThymeleafViewResolver resolver = buildResolver();
        View first = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        View second = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertNotSame(first, second, "cache=false 必须保持热改语义，不缓存 View");
    }

    @Test
    void resolveViewName_enabledFalse_isDisabledAndReturnsNull() throws Exception {
        when(props.get(ViewProperties.THYMELEAF_ENABLED, null)).thenReturn("false");
        ThymeleafViewResolver resolver = buildResolver();
        assertFalse(resolver.isEnabled());
        assertNull(resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class)));
    }

    @Test
    void getOrder_defaultIsDeterministic() {
        assertEquals(ViewProperties.THYMELEAF_ORDER_DEFAULT, buildResolver().getOrder());
        assertTrue(ViewProperties.THYMELEAF_ORDER_DEFAULT < ViewProperties.FREEMARKER_ORDER_DEFAULT);
        assertTrue(ViewProperties.FREEMARKER_ORDER_DEFAULT < ViewProperties.BEETL_ORDER_DEFAULT);
        assertTrue(ViewProperties.BEETL_ORDER_DEFAULT < ViewProperties.JSP_ORDER_DEFAULT);
    }

    @Test
    void selectProvider_prefersFirstSupportingProvider() {
        io.springperf.web.view.WebExchangeProvider first = mock(io.springperf.web.view.WebExchangeProvider.class);
        when(first.supports(any(WebServerHttpRequest.class))).thenReturn(false);
        io.springperf.web.view.WebExchangeProvider second = mock(io.springperf.web.view.WebExchangeProvider.class);
        when(second.supports(any(WebServerHttpRequest.class))).thenReturn(true);
        when(webContext.getWebComponents(io.springperf.web.view.WebExchangeProvider.class))
                .thenReturn(java.util.List.of(first, second));

        ThymeleafViewResolver resolver = buildResolver();
        assertSame(second, resolver.selectProvider(mock(WebServerHttpRequest.class)));
    }

    @Test
    void selectProvider_noMatch_fallsBackToDefault() {
        when(webContext.getWebComponents(io.springperf.web.view.WebExchangeProvider.class))
                .thenReturn(java.util.Collections.emptyList());
        ThymeleafViewResolver resolver = buildResolver();
        assertInstanceOf(io.springperf.web.view.DefaultWebExchangeProvider.class,
                resolver.selectProvider(mock(WebServerHttpRequest.class)));
    }
}
