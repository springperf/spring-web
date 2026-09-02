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
        when(props.get(ViewProperties.ENCODING, ViewProperties.ENCODING_DEFAULT)).thenReturn("UTF-8");
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
}
