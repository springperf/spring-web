package io.springperf.web.view.freemarker;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FreemarkerViewResolverTest {

    private ApplicationProperties props;
    private WebContext webContext;

    @BeforeEach
    void setUp() {
        props = mock(ApplicationProperties.class);
        when(props.get(ViewProperties.FREEMARKER_PREFIX, ViewProperties.FREEMARKER_PREFIX_DEFAULT))
                .thenReturn("templates/");
        when(props.get(ViewProperties.FREEMARKER_SUFFIX, ViewProperties.FREEMARKER_SUFFIX_DEFAULT)).thenReturn(".ftl");
        // cache 由 ViewProperties.resolveBoolean 经 props.get(key, null) 读取，getBoolean 是死桩；
        // 未配置即回退默认 true，覆盖 false 的用例在方法内用 props.get(eq(KEY), isNull()) 精确桩。
        webContext = mock(WebContext.class);
        when(webContext.getProps()).thenReturn(props);
    }

    private FreemarkerViewResolver buildResolver() {
        FreemarkerViewResolver resolver = new FreemarkerViewResolver();
        resolver.initWithWebContext(webContext);
        return resolver;
    }

    /**
     * 锁定引擎的 incompatibleImprovements：必须显式取 2.3.32（Boot 2.7.18 BOM 管理的版本），
     * 不能用 DEFAULT_INCOMPATIBLE_IMPROVEMENTS——它的取值是 2.3.0，会把 recognizeStandardFileExtensions
     * 等开关退回 2.3.0 语义（.ftlh 等标准扩展名不再被识别），且不会让任何测试失败。
     */
    @Test
    void engine_usesVersion232IncompatibleImprovements() throws Exception {
        FreemarkerViewResolver resolver = buildResolver();
        java.lang.reflect.Field field = FreemarkerViewResolver.class.getDeclaredField("configuration");
        field.setAccessible(true);
        freemarker.template.Configuration cfg = (freemarker.template.Configuration) field.get(resolver);

        assertEquals(freemarker.template.Configuration.VERSION_2_3_32.toString(),
                cfg.getIncompatibleImprovements().toString());
        assertTrue(cfg.getRecognizeStandardFileExtensions(),
                "标准扩展名识别依赖 2.3.32 语义，退回 2.3.0 会静默改变模板处理方式");
    }

    @Test
    void resolveViewName_existingTemplate_returnsView() throws Exception {
        FreemarkerViewResolver resolver = buildResolver();
        View view = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertNotNull(view);
    }

    @Test
    void resolveViewName_nameWithSuffix_resolves() throws Exception {
        FreemarkerViewResolver resolver = buildResolver();
        View view = resolver.resolveViewName("hello.ftl", Locale.US, mock(WebServerHttpRequest.class));
        assertNotNull(view);

        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(resp.getBody()).thenReturn(body);
        Map<String, Object> model = new HashMap<>();
        model.put("name", "Suffix");
        view.render(model, mock(WebServerHttpRequest.class), resp);

        assertTrue(new String(body.toByteArray(), StandardCharsets.UTF_8).contains("Hello, Suffix!"),
                "后缀不应被拼成 hello.ftl.ftl");
    }

    @Test
    void resolveViewName_missingTemplate_returnsNull() throws Exception {
        FreemarkerViewResolver resolver = buildResolver();
        assertNull(resolver.resolveViewName("does-not-exist", Locale.US, mock(WebServerHttpRequest.class)));
    }

    @Test
    void viewRender_rendersTemplateWithModel() throws Exception {
        FreemarkerViewResolver resolver = buildResolver();
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
        assertTrue(output.contains("Hello, Perf!"), "模板文本与 model 值应组成完整输出: " + output);
        assertFalse(output.contains("${name}"), "FreeMarker 表达式必须被求值: " + output);
    }

    @Test
    void viewRender_htmlEscapesModelValues() throws Exception {
        // 回归：模板以 <#ftl output_format="HTML"> 声明输出格式，model 中的 HTML 元字符必须被转义，
        // 否则本可执行 ?name=<script>alert(1)</script> 之类输入造成 XSS。
        FreemarkerViewResolver resolver = buildResolver();
        View view = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));

        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(resp.getBody()).thenReturn(body);

        Map<String, Object> model = new HashMap<>();
        model.put("name", "<script>alert(1)</script>");
        view.render(model, req, resp);

        String output = new String(body.toByteArray(), StandardCharsets.UTF_8);
        assertFalse(output.contains("<script>"), "model 中的 HTML 不应原样输出: " + output);
        assertTrue(output.contains("&lt;script&gt;"), "HTML 元字符应被转义: " + output);
    }

    @Test
    void viewRender_concurrentSameView_doesNotMutateSharedTemplate() throws Exception {
        FreemarkerViewResolver resolver = buildResolver();
        View view = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertNotNull(view);

        // 提取底层共享 Template（缓存开启时为同一实例）。修复前 render 会调用
        // template.setOutputEncoding(...)，在并发渲染同一视图时产生数据竞争并互相覆盖编码。
        java.lang.reflect.Field templateField = view.getClass().getDeclaredField("template");
        templateField.setAccessible(true);
        freemarker.template.Template template = (freemarker.template.Template) templateField.get(view);

        int threads = 32;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        java.util.List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<Throwable>());
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    WebServerHttpRequest req = mock(WebServerHttpRequest.class);
                    WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
                    when(resp.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);
                    ByteArrayOutputStream body = new ByteArrayOutputStream();
                    when(resp.getBody()).thenReturn(body);
                    Map<String, Object> model = new HashMap<>();
                    model.put("name", "Perf" + idx);
                    view.render(model, req, resp);
                    String out = new String(body.toByteArray(), StandardCharsets.UTF_8);
                    if (!out.contains("Perf" + idx)) {
                        errors.add(new AssertionError("缺少 model 值 (thread " + idx + "): " + out));
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS), "并发渲染应在 10s 内完成");
        assertTrue(errors.isEmpty(), "并发渲染不应抛异常或输出错乱: " + errors);
        // 关键回归：共享 Template 实例的 outputEncoding 不应被并发渲染改写（修复前为 "UTF-8"）
        assertNull(template.getOutputEncoding(), "共享 Template 的 outputEncoding 不应被并发渲染改写");
    }

    @Test
    void resolveViewName_cached_returnsSameViewInstance() throws Exception {
        FreemarkerViewResolver resolver = buildResolver();
        View first = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        View second = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertSame(first, second);
    }

    @Test
    void resolveViewName_differentLocale_usesDifferentCacheKey() throws Exception {
        FreemarkerViewResolver resolver = buildResolver();
        View us = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        View fr = resolver.resolveViewName("hello", Locale.FRANCE, mock(WebServerHttpRequest.class));
        assertNotNull(us);
        assertNotNull(fr);
        assertNotSame(us, fr, "FreeMarker Template 与 locale 相关，缓存键必须区分 locale");
    }

    @Test
    void resolveViewName_cacheDisabled_returnsFreshView() throws Exception {
        when(props.get(ViewProperties.FREEMARKER_CACHE, null)).thenReturn("false");
        FreemarkerViewResolver resolver = buildResolver();
        View first = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        View second = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertNotSame(first, second);
    }

    @Test
    void resolveViewName_enabledFalse_isDisabledAndReturnsNull() throws Exception {
        when(props.get(ViewProperties.FREEMARKER_ENABLED, null)).thenReturn("false");
        FreemarkerViewResolver resolver = buildResolver();
        assertFalse(resolver.isEnabled());
        assertNull(resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class)));
    }

    @Test
    void getOrder_defaultIsExplicit() {
        assertEquals(ViewProperties.FREEMARKER_ORDER_DEFAULT, buildResolver().getOrder());
    }

    @Test
    void viewRender_contentTypeProvidedByView_notWrittenDuringRender() throws Exception {
        when(props.get(ViewProperties.FREEMARKER_CONTENT_TYPE, ViewProperties.FREEMARKER_CONTENT_TYPE_DEFAULT))
                .thenReturn("text/html;charset=ISO-8859-1");
        FreemarkerViewResolver resolver = buildResolver();
        View view = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertEquals("text/html;charset=ISO-8859-1", view.getContentType());

        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getCharacterEncoding()).thenReturn(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(resp.getBody()).thenReturn(body);
        Map<String, Object> model = new HashMap<>();
        model.put("name", "Perf");
        view.render(model, mock(WebServerHttpRequest.class), resp);

        verify(resp, never()).getHeaders();
    }

    @Test
    void initWithWebContext_invalidContentType_failsFast() {
        when(props.get(ViewProperties.FREEMARKER_CONTENT_TYPE, ViewProperties.FREEMARKER_CONTENT_TYPE_DEFAULT))
                .thenReturn("not a media type");
        IllegalStateException ex = assertThrows(IllegalStateException.class, this::buildResolver);
        assertTrue(ex.getMessage().contains(ViewProperties.FREEMARKER_CONTENT_TYPE));
    }

    @Test
    void invalidateCache_reloadsView() throws Exception {
        FreemarkerViewResolver resolver = buildResolver();
        View first = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        assertSame(first, resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class)));

        resolver.invalidateCache();
        assertNotSame(first, resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class)));
    }

    @Test
    void invalidateCache_clearsAllLocaleCaches() throws Exception {
        FreemarkerViewResolver resolver = buildResolver();
        View us = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        View fr = resolver.resolveViewName("hello", Locale.FRANCE, mock(WebServerHttpRequest.class));

        resolver.invalidateCache();

        assertNotSame(us, resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class)));
        assertNotSame(fr, resolver.resolveViewName("hello", Locale.FRANCE, mock(WebServerHttpRequest.class)));
    }

    @Test
    void invalidateCache_clearsEngineTemplateCache() throws Exception {
        FreemarkerViewResolver resolver = buildResolver();
        freemarker.template.Configuration configuration = mock(freemarker.template.Configuration.class);
        java.lang.reflect.Field field = FreemarkerViewResolver.class.getDeclaredField("configuration");
        field.setAccessible(true);
        field.set(resolver, configuration);

        resolver.invalidateCache();

        verify(configuration).clearTemplateCache();
    }
}
