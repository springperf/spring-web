package io.springperf.web.view.freemarker;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewProperties;

/** Freemarker 配置解析、启动期 fail-fast、缓存与渲染编码的边界分支。 */
class FreemarkerViewResolverSettingsTest {

    private ApplicationProperties props;
    private WebContext webContext;

    @BeforeEach
    void setUp() {
        props = mock(ApplicationProperties.class);
        lenient().when(props.get(anyString(), any())).thenAnswer(inv -> inv.getArgument(1));
        lenient().when(props.get(eq(ViewProperties.FREEMARKER_PREFIX), any())).thenReturn("templates/");
        lenient().when(props.get(eq(ViewProperties.FREEMARKER_SUFFIX), any())).thenReturn(".ftl");
        webContext = mock(WebContext.class);
        lenient().when(webContext.getProps()).thenReturn(props);
    }

    private FreemarkerViewResolver resolver() {
        FreemarkerViewResolver resolver = new FreemarkerViewResolver();
        resolver.initWithWebContext(webContext);
        return resolver;
    }

    private static void stubNullable(ApplicationProperties props, String key, String value) {
        when(props.get(eq(key), isNull())).thenReturn(value);
    }

    private static Properties settingsOf(FreemarkerViewResolver resolver) throws Exception {
        Field field = FreemarkerViewResolver.class.getDeclaredField("settings");
        field.setAccessible(true);
        return (Properties) field.get(resolver);
    }

    @Test
    void validSettingsAreParsedIntoProperties() throws Exception {
        stubNullable(props, ViewProperties.FREEMARKER_SETTINGS, "datetime_format=yyyy, number_format=0.##");

        Properties settings = settingsOf(resolver());

        assertEquals("yyyy", settings.getProperty("datetime_format"));
        assertEquals("0.##", settings.getProperty("number_format"));
        assertEquals(2, settings.size());
    }

    @Test
    void blankSettingsAreIgnored() throws Exception {
        stubNullable(props, ViewProperties.FREEMARKER_SETTINGS, "   ");

        assertNull(settingsOf(resolver()));
    }

    @Test
    void settingsWithEmptyEntriesAreSkipped() throws Exception {
        stubNullable(props, ViewProperties.FREEMARKER_SETTINGS, " , , datetime_format=yyyy , ");

        Properties settings = settingsOf(resolver());

        assertEquals(1, settings.size());
        assertEquals("yyyy", settings.getProperty("datetime_format"));
    }

    @Test
    void settingWithoutEqualsFailsFast() {
        stubNullable(props, ViewProperties.FREEMARKER_SETTINGS, "boolean_format");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, this::resolver);
        assertTrue(ex.getMessage().contains("expected key=value"));
    }

    @Test
    void unknownEngineSettingFailsFast() {
        stubNullable(props, ViewProperties.FREEMARKER_SETTINGS, "no_such_freemarker_setting=1");

        IllegalStateException ex = assertThrows(IllegalStateException.class, this::resolver);
        assertTrue(ex.getMessage().contains("spring.freemarker.settings"));
    }

    @Test
    void invalidContentTypeFailsFast() {
        when(props.get(eq(ViewProperties.FREEMARKER_CONTENT_TYPE), any())).thenReturn("not a media type");

        IllegalStateException ex = assertThrows(IllegalStateException.class, this::resolver);
        assertTrue(ex.getMessage().contains(ViewProperties.FREEMARKER_CONTENT_TYPE));
    }

    @Test
    void nullContentTypeFallsBackToDefault() throws Exception {
        when(props.get(eq(ViewProperties.FREEMARKER_CONTENT_TYPE), any())).thenReturn(null);

        View view = resolver().resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));

        assertNotNull(view);
        assertEquals(ViewProperties.FREEMARKER_CONTENT_TYPE_DEFAULT, view.getContentType());
    }

    @Test
    void disabledResolverResolvesNoViewAndInvalidateCacheIsSafe() throws Exception {
        stubNullable(props, ViewProperties.FREEMARKER_ENABLED, "false");
        FreemarkerViewResolver resolver = resolver();

        assertFalse(resolver.isEnabled());
        assertNull(resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class)));
        resolver.invalidateCache();
        assertNull(resolver.resolveViewName("hello", null, mock(WebServerHttpRequest.class)),
                "禁用后清理缓存也不应恢复解析");
    }

    @Test
    void cacheDisabledResolvesFreshViewAfterInvalidation() throws Exception {
        stubNullable(props, ViewProperties.FREEMARKER_CACHE, "false");
        FreemarkerViewResolver resolver = resolver();
        WebServerHttpRequest request = mock(WebServerHttpRequest.class);

        View first = resolver.resolveViewName("hello", null, request);
        resolver.invalidateCache();
        View second = resolver.resolveViewName("hello", null, request);

        assertNotNull(first);
        assertNotNull(second);
        assertNotSame(first, second, "关闭缓存后每次解析都应产生新 View");
    }

    @Test
    void renderWithoutResponseCharsetUsesUtf8() throws Exception {
        FreemarkerViewResolver resolver = resolver();
        View view = resolver.resolveViewName("hello", Locale.US, mock(WebServerHttpRequest.class));
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(resp.getCharacterEncoding()).thenReturn(null);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(resp.getBody()).thenReturn(body);
        Map<String, Object> model = new HashMap<>();
        model.put("name", "Perf");

        view.render(model, mock(WebServerHttpRequest.class), resp);

        assertTrue(new String(body.toByteArray(), StandardCharsets.UTF_8).contains("Perf"));
    }
}
