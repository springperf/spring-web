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

        // 模板文件带 UTF-8 BOM，输出首字符是 U+FEFF，因此不做整串等值比较
        String output = new String(body.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(output.contains("Hello, Perf!"), "模板文本与 model 值必须在正确位置被替换: " + output);
        assertFalse(output.contains("${name}"), "Beetl 表达式必须被求值: " + output);
    }

    @Test
    void viewRender_nameWithSuffix_rendersSameTemplate() throws Exception {
        BeetlViewResolver resolver = buildResolver();
        View view = resolver.resolveViewName("hello.btl", Locale.US, mock(WebServerHttpRequest.class));
        assertNotNull(view, "带后缀的视图名应能解析");

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(resp.getBody()).thenReturn(body);

        Map<String, Object> model = new HashMap<>();
        model.put("name", "Suffix");
        view.render(model, req, resp);

        String output = new String(body.toByteArray(), StandardCharsets.UTF_8);
        assertTrue(output.contains("Hello, Suffix!"), "模板路径不应被拼成 hello.btl.btl: " + output);
        assertFalse(output.contains("${name}"), "Beetl 表达式必须被求值: " + output);
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
        assertFalse(resolver.isEnabled());
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
    void initWithWebContext_cacheDisabled_usesNoCache() throws Exception {
        ApplicationProperties noCacheProps = mock(ApplicationProperties.class);
        when(noCacheProps.get(ViewProperties.BEETL_PREFIX, ViewProperties.BEETL_PREFIX_DEFAULT))
                .thenReturn("templates/");
        when(noCacheProps.get(ViewProperties.BEETL_SUFFIX, ViewProperties.BEETL_SUFFIX_DEFAULT)).thenReturn(".btl");
        when(noCacheProps.get(ViewProperties.BEETL_CACHE, null)).thenReturn("false");
        WebContext noCacheContext = mock(WebContext.class);
        when(noCacheContext.getProps()).thenReturn(noCacheProps);

        BeetlViewResolver resolver = new BeetlViewResolver();
        resolver.initWithWebContext(noCacheContext);

        assertEquals(BeetlViewResolver.NO_CACHE_CLASS, groupTemplateOf(resolver).getProgramCache().getClass().getName(),
                "cache=false 必须切到 NoCache 实现，否则运行期替换模板不会生效");
    }

    @Test
    void initWithWebContext_cacheTtlConfigured_setsCacheDuration() throws Exception {
        ApplicationProperties ttlProps = mock(ApplicationProperties.class);
        when(ttlProps.get(ViewProperties.BEETL_PREFIX, ViewProperties.BEETL_PREFIX_DEFAULT)).thenReturn("templates/");
        when(ttlProps.get(ViewProperties.BEETL_SUFFIX, ViewProperties.BEETL_SUFFIX_DEFAULT)).thenReturn(".btl");
        when(ttlProps.getDurationMillis(ViewProperties.BEETL_CACHE_TTL, -1L)).thenReturn(60_000L);
        WebContext ttlContext = mock(WebContext.class);
        when(ttlContext.getProps()).thenReturn(ttlProps);

        BeetlViewResolver resolver = new BeetlViewResolver();
        resolver.initWithWebContext(ttlContext);

        assertEquals("60", groupTemplateOf(resolver).getConf().getPs().getProperty("cache.duration"),
                "cache-ttl=60000ms 应换算为 Beetl 的 60 秒 cache.duration");
    }

    private static org.beetl.core.GroupTemplate groupTemplateOf(BeetlViewResolver resolver) throws Exception {
        java.lang.reflect.Field field = BeetlViewResolver.class.getDeclaredField("groupTemplate");
        field.setAccessible(true);
        return (org.beetl.core.GroupTemplate) field.get(resolver);
    }

    @Test
    void resolveViewName_cached_returnsSameViewInstance() throws Exception {
        BeetlViewResolver resolver = buildResolver();
        View first = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        View second = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertSame(first, second);
    }

    @Test
    void resolveViewName_cacheDisabled_returnsFreshView() throws Exception {
        when(props.get(ViewProperties.BEETL_CACHE, null)).thenReturn("false");
        BeetlViewResolver resolver = buildResolver();
        View first = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        View second = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertNotSame(first, second);
    }

    @Test
    void getOrder_defaultIsExplicit() {
        assertEquals(ViewProperties.BEETL_ORDER_DEFAULT, buildResolver().getOrder());
    }

    @Test
    void invalidateCache_reloadsView() throws Exception {
        BeetlViewResolver resolver = buildResolver();
        View first = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertSame(first, resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class)));

        resolver.invalidateCache();
        assertNotSame(first, resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class)));
    }

    @Test
    void invalidateCache_clearsEngineProgramCache() throws Exception {
        BeetlViewResolver resolver = buildResolver();
        org.beetl.core.GroupTemplate groupTemplate = mock(org.beetl.core.GroupTemplate.class);
        org.beetl.core.runtime.IBeetlCache programCache = mock(org.beetl.core.runtime.IBeetlCache.class);
        when(groupTemplate.getProgramCache()).thenReturn(programCache);
        java.lang.reflect.Field field = BeetlViewResolver.class.getDeclaredField("groupTemplate");
        field.setAccessible(true);
        field.set(resolver, groupTemplate);

        resolver.invalidateCache();

        org.mockito.Mockito.verify(programCache).clearAll();
    }
}
