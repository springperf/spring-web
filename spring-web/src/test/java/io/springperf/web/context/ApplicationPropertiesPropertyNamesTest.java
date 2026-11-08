package io.springperf.web.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/** {@link ApplicationProperties#getPropertyNames(String)} 与热字段逐字段容错（配置中心脏推送）。 */
class ApplicationPropertiesPropertyNamesTest {

    private static final String PREFIX = "server.servlet.context-parameters.";

    private static ApplicationProperties with(MockEnvironment env) {
        ApplicationProperties props = new ApplicationProperties();
        props.setEnvironment(env);
        return props;
    }

    @Test
    void getPropertyNames_filtersByPrefixAndDeduplicatesAcrossSources() {
        MockEnvironment env = new MockEnvironment().withProperty(PREFIX + "alpha", "1").withProperty("unrelated.key",
                "x");
        env.getPropertySources()
                .addFirst(new MapPropertySource("second", Map.of(PREFIX + "alpha", "2", PREFIX + "beta", "3")));

        List<String> names = with(env).getPropertyNames(PREFIX);

        assertEquals(2, names.size(), "同名键跨 PropertySource 只应出现一次");
        assertTrue(names.contains(PREFIX + "alpha"));
        assertTrue(names.contains(PREFIX + "beta"));
    }

    @Test
    void getPropertyNames_skipsNonEnumerablePropertySources() {
        MockEnvironment env = new MockEnvironment().withProperty(PREFIX + "visible", "1");
        PropertySource<Object> opaque = new PropertySource<Object>("opaque", new Object()) {
            @Override
            public Object getProperty(String name) {
                return PREFIX.equals(name) ? "hidden" : null;
            }
        };
        env.getPropertySources().addFirst(opaque);

        assertEquals(List.of(PREFIX + "visible"), with(env).getPropertyNames(PREFIX));
    }

    @Test
    void getPropertyNames_nullPrefixAndNoMatchReturnEmpty() {
        ApplicationProperties props = with(new MockEnvironment().withProperty(PREFIX + "alpha", "1"));

        assertTrue(props.getPropertyNames(null).isEmpty());
        assertTrue(props.getPropertyNames("no.such.prefix.").isEmpty());
    }

    @Test
    void getPropertyNames_withoutEnvironmentReturnsEmpty() {
        assertTrue(new ApplicationProperties().getPropertyNames(PREFIX).isEmpty());
    }

    @Test
    void getPropertyNames_nonConfigurableEnvironmentReturnsEmpty() {
        Environment plain = mock(Environment.class);
        when(plain.getProperty(anyString())).thenReturn(null);
        ApplicationProperties props = with(new MockEnvironment());
        ReflectionTestUtils.setField(props, "environment", plain);

        assertTrue(props.getPropertyNames(PREFIX).isEmpty());
    }

    @Test
    void clearCache_badMaxParameterCount_keepsOldValueAndRefreshesOtherFields() {
        MockEnvironment env = new MockEnvironment().withProperty(PropertiesConstant.SERVER_MAX_PARAMETER_COUNT, "100")
                .withProperty(PropertiesConstant.HTTP_TIMEOUT, "10s")
                .withProperty(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE, "1024");
        ApplicationProperties props = with(env);
        assertEquals(100, props.getMaxParameterCount());

        env.setProperty(PropertiesConstant.SERVER_MAX_PARAMETER_COUNT, "not-a-number");
        env.setProperty(PropertiesConstant.HTTP_TIMEOUT, "20s");
        env.setProperty(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE, "2048");
        props.clearCache();

        assertEquals(100, props.getMaxParameterCount(), "单个热键解析失败应保留旧快照");
        assertEquals(20000L, props.getHttpTimeoutMillis());
        assertEquals(2048, props.getMaxInMemorySize());
    }

    @Test
    void clearCache_badDuration_keepsOldValueAndRefreshesOtherFields() {
        MockEnvironment env = new MockEnvironment().withProperty(PropertiesConstant.SERVER_MAX_PARAMETER_COUNT, "100")
                .withProperty(PropertiesConstant.HTTP_TIMEOUT, "10s")
                .withProperty(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE, "1024");
        ApplicationProperties props = with(env);
        assertEquals(10000L, props.getHttpTimeoutMillis());

        env.setProperty(PropertiesConstant.SERVER_MAX_PARAMETER_COUNT, "200");
        env.setProperty(PropertiesConstant.HTTP_TIMEOUT, "10q");
        env.setProperty(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE, "2048");
        props.clearCache();

        assertEquals(10000L, props.getHttpTimeoutMillis(), "未知 Duration 单位不应清空旧快照");
        assertEquals(200, props.getMaxParameterCount());
        assertEquals(2048, props.getMaxInMemorySize());
    }

    @Test
    void clearCache_badMaxInMemorySize_keepsOldValueAndRefreshesOtherFields() {
        MockEnvironment env = new MockEnvironment().withProperty(PropertiesConstant.SERVER_MAX_PARAMETER_COUNT, "100")
                .withProperty(PropertiesConstant.HTTP_TIMEOUT, "10s")
                .withProperty(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE, "1024");
        ApplicationProperties props = with(env);
        assertEquals(1024, props.getMaxInMemorySize());

        env.setProperty(PropertiesConstant.SERVER_MAX_PARAMETER_COUNT, "200");
        env.setProperty(PropertiesConstant.HTTP_TIMEOUT, "20s");
        env.setProperty(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE, "oops");
        props.clearCache();

        assertEquals(1024, props.getMaxInMemorySize());
        assertEquals(200, props.getMaxParameterCount());
        assertEquals(20000L, props.getHttpTimeoutMillis());
    }
}
