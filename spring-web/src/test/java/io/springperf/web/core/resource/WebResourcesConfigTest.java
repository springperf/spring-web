package io.springperf.web.core.resource;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WebResourcesConfig} 单元测试：{@code spring.web.resources.*} 解析。
 */
class WebResourcesConfigTest {

    private static ApplicationProperties props(String staticLocations, String cachePeriod) {
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(props.get(PropertiesConstant.WEB_RESOURCES_STATIC_LOCATIONS,
                        PropertiesConstant.WEB_RESOURCES_STATIC_LOCATIONS_DEFAULT))
                .thenReturn(staticLocations);
        lenient().when(props.get(PropertiesConstant.WEB_RESOURCES_CACHE_CONTROL_MAX_AGE, null)).thenReturn(null);
        lenient().when(props.get(PropertiesConstant.WEB_RESOURCES_CACHE_PERIOD, null)).thenReturn(cachePeriod);
        return props;
    }

    @Test
    void defaults_addMappingsDisabled_bootLocations() {
        ApplicationProperties p = props(
                PropertiesConstant.WEB_RESOURCES_STATIC_LOCATIONS_DEFAULT, null);
        WebResourcesConfig cfg = WebResourcesConfig.fromProperties(p);
        // 默认关闭自动映射（保持框架历史行为）
        assertFalse(cfg.isAddMappings());
        assertEquals(List.of("classpath:/META-INF/resources", "classpath:/resources",
                "classpath:/static", "classpath:/public"), cfg.getStaticLocations());
        assertNull(cfg.getCachePeriodSeconds());
    }

    @Test
    void addMappings_enabledFromProperties() {
        ApplicationProperties p = props(
                PropertiesConstant.WEB_RESOURCES_STATIC_LOCATIONS_DEFAULT, null);
        when(p.getBoolean(PropertiesConstant.WEB_RESOURCES_ADD_MAPPINGS,
                PropertiesConstant.WEB_RESOURCES_ADD_MAPPINGS_DEFAULT)).thenReturn(true);
        assertTrue(WebResourcesConfig.fromProperties(p).isAddMappings());
    }

    @Test
    void parseLocations_trimsAndStripsTrailingSlash() {
        ApplicationProperties p = props("classpath:/a/, classpath:/b , file:/c/", null);
        WebResourcesConfig cfg = WebResourcesConfig.fromProperties(p);
        assertEquals(List.of("classpath:/a", "classpath:/b", "file:/c"), cfg.getStaticLocations());
    }

    @Test
    void cachePeriod_duration_parsedToSeconds() {
        ApplicationProperties p = props(
                PropertiesConstant.WEB_RESOURCES_STATIC_LOCATIONS_DEFAULT, "1h");
        when(p.getDurationSeconds(PropertiesConstant.WEB_RESOURCES_CACHE_PERIOD, 0L)).thenReturn(3600L);
        WebResourcesConfig cfg = WebResourcesConfig.fromProperties(p);
        assertEquals(3600L, cfg.getCachePeriodSeconds());
    }

    @Test
    void cacheControlMaxAge_takesPrecedenceOverCachePeriod() {
        ApplicationProperties p = props(
                PropertiesConstant.WEB_RESOURCES_STATIC_LOCATIONS_DEFAULT, "1h");
        when(p.get(PropertiesConstant.WEB_RESOURCES_CACHE_CONTROL_MAX_AGE, null)).thenReturn("10m");
        when(p.getDurationSeconds(PropertiesConstant.WEB_RESOURCES_CACHE_CONTROL_MAX_AGE, 0L)).thenReturn(600L);
        WebResourcesConfig cfg = WebResourcesConfig.fromProperties(p);
        assertEquals(600L, cfg.getCachePeriodSeconds(), "max-age 应优先于 cache.period");
    }

    @Test
    void parseLocations_blank_returnsEmpty() {
        assertEquals(List.of(), WebResourcesConfig.parseLocations(null));
        assertEquals(List.of(), WebResourcesConfig.parseLocations("  "));
        assertEquals(List.of(), WebResourcesConfig.parseLocations(" , , "));
    }

    @Test
    void defaultConstant_addMappingsDisabled() {
        assertFalse(WebResourcesConfig.DEFAULT.isAddMappings());
        assertEquals(4, WebResourcesConfig.DEFAULT.getStaticLocations().size());
    }
}
