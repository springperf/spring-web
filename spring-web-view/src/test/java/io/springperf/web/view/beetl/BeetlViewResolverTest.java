package io.springperf.web.view.beetl;

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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BeetlViewResolverTest {

    private ApplicationProperties props;
    private WebContext webContext;

    @BeforeEach
    void setUp() {
        props = mock(ApplicationProperties.class);
        when(props.get(ViewProperties.BEETL_PREFIX, ViewProperties.BEETL_PREFIX_DEFAULT)).thenReturn("templates/");
        when(props.get(ViewProperties.BEETL_SUFFIX, ViewProperties.BEETL_SUFFIX_DEFAULT)).thenReturn(".btl");
        // 兜底：resolveBoolean 走 get(key, null)，Mockito 未桩时返回 null（视为“未配置” → 默认 true）。
        // resolveDurationMillis 走 getDurationMillis：未桩时返回默认值。
        lenient().when(props.getDurationMillis(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong())).thenAnswer(inv -> inv.getArgument(1));
        webContext = mock(WebContext.class);
        when(webContext.getProps()).thenReturn(props);
    }

    private BeetlViewResolver buildResolver() {
        BeetlViewResolver resolver = new BeetlViewResolver();
        resolver.initWithWebContext(webContext);
        return resolver;
    }

    @Test
    void resolveViewName_existingTemplate_returnsView() throws Exception {
        BeetlViewResolver resolver = buildResolver();
        View view = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertNotNull(view);
    }

    @Test
    void resolveViewName_missingTemplate_returnsNull() throws Exception {
        BeetlViewResolver resolver = buildResolver();
        assertNull(resolver.resolveViewName("does-not-exist", Locale.US, mock(WebServerHttpRequest.class)));
    }

    @Test
    void viewRender_rendersTemplateWithModel() throws Exception {
        BeetlViewResolver resolver = buildResolver();
        View view = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
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

    // ==================== spring.beetl.enabled / cache / cache-ttl ====================

    @Test
    void resolveViewName_enabledFalse_returnsNull() throws Exception {
        // ViewProperties.resolveBoolean 走 get(key, null)：桩该调用返回 "false"
        ApplicationProperties disabledProps = mock(ApplicationProperties.class);
        when(disabledProps.get(ViewProperties.BEETL_PREFIX, ViewProperties.BEETL_PREFIX_DEFAULT))
                .thenReturn("templates/");
        when(disabledProps.get(ViewProperties.BEETL_SUFFIX, ViewProperties.BEETL_SUFFIX_DEFAULT)).thenReturn(".btl");
        when(disabledProps.get(ViewProperties.BEETL_ENABLED, null)).thenReturn("false");
        WebContext disabledContext = mock(WebContext.class);
        when(disabledContext.getProps()).thenReturn(disabledProps);

        BeetlViewResolver resolver = new BeetlViewResolver();
        resolver.initWithWebContext(disabledContext);
        // 即使模板存在，enabled=false 也应返回 null
        assertNull(resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class)));
    }

    @Test
    void resolveViewName_enabledTrue_returnsView() throws Exception {
        // setUp 兜底桩：enabled 默认 true → 模板存在时返回视图
        BeetlViewResolver resolver = buildResolver();
        assertNotNull(resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class)));
    }

    @Test
    void initWithWebContext_cacheDisabled_doesNotThrow() {
        ApplicationProperties noCacheProps = mock(ApplicationProperties.class);
        when(noCacheProps.get(ViewProperties.BEETL_PREFIX, ViewProperties.BEETL_PREFIX_DEFAULT))
                .thenReturn("templates/");
        when(noCacheProps.get(ViewProperties.BEETL_SUFFIX, ViewProperties.BEETL_SUFFIX_DEFAULT)).thenReturn(".btl");
        when(noCacheProps.get(ViewProperties.BEETL_CACHE, null)).thenReturn("false");
        WebContext noCacheContext = mock(WebContext.class);
        when(noCacheContext.getProps()).thenReturn(noCacheProps);

        assertDoesNotThrow(() -> {
            BeetlViewResolver resolver = new BeetlViewResolver();
            resolver.initWithWebContext(noCacheContext);
        }, "cache=false 应使用 NoCache 且正常初始化");
    }

    @Test
    void initWithWebContext_cacheTtlConfigured_doesNotThrow() {
        ApplicationProperties ttlProps = mock(ApplicationProperties.class);
        when(ttlProps.get(ViewProperties.BEETL_PREFIX, ViewProperties.BEETL_PREFIX_DEFAULT)).thenReturn("templates/");
        when(ttlProps.get(ViewProperties.BEETL_SUFFIX, ViewProperties.BEETL_SUFFIX_DEFAULT)).thenReturn(".btl");
        when(ttlProps.getDurationMillis(ViewProperties.BEETL_CACHE_TTL, -1L)).thenReturn(60_000L);
        WebContext ttlContext = mock(WebContext.class);
        when(ttlContext.getProps()).thenReturn(ttlProps);

        assertDoesNotThrow(() -> {
            BeetlViewResolver resolver = new BeetlViewResolver();
            resolver.initWithWebContext(ttlContext);
        }, "cache-ttl 配置应正常初始化");
    }
}
