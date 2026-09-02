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
        when(props.get(ViewProperties.FREEMARKER_PREFIX, ViewProperties.FREEMARKER_PREFIX_DEFAULT)).thenReturn("templates/");
        when(props.get(ViewProperties.FREEMARKER_SUFFIX, ViewProperties.FREEMARKER_SUFFIX_DEFAULT)).thenReturn(".ftl");
        when(props.getBoolean(ViewProperties.FREEMARKER_CACHE, ViewProperties.FREEMARKER_CACHE_DEFAULT)).thenReturn(true);
        when(props.get(ViewProperties.ENCODING, ViewProperties.ENCODING_DEFAULT)).thenReturn("UTF-8");
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
}
