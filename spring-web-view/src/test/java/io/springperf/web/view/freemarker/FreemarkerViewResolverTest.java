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
        when(props.getBoolean(ViewProperties.FREEMARKER_CACHE, ViewProperties.FREEMARKER_CACHE_DEFAULT))
                .thenReturn(true);
        webContext = mock(WebContext.class);
        when(webContext.getProps()).thenReturn(props);
    }

    private FreemarkerViewResolver buildResolver() {
        FreemarkerViewResolver resolver = new FreemarkerViewResolver();
        resolver.initWithWebContext(webContext);
        return resolver;
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
        assertTrue(output.contains("Hello"), "渲染输出应包含模板内容: " + output);
        assertTrue(output.contains("Perf"), "渲染输出应包含 model 值: " + output);
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
}
