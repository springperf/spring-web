package io.springperf.web.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;

/**
 * {@link MultipartConfig} 单元测试：{@code spring.servlet.multipart.*} 解析与 与 {@code server.http.max-content-length} 的回退关系。
 */
class MultipartConfigTest {

    private static ApplicationProperties props(String maxFileSize, String maxRequestSize) {
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(props.get(PropertiesConstant.SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE_DEFAULT)).thenReturn(maxFileSize);
        lenient()
                .when(props.get(PropertiesConstant.SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE,
                        PropertiesConstant.SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE_DEFAULT))
                .thenReturn(maxRequestSize);
        // 兜底：未显式桩化 enabled 时返回默认 true
        lenient()
                .when(props.getBoolean(PropertiesConstant.SPRING_SERVLET_MULTIPART_ENABLED,
                        PropertiesConstant.SPRING_SERVLET_MULTIPART_ENABLED_DEFAULT))
                .thenReturn(PropertiesConstant.SPRING_SERVLET_MULTIPART_ENABLED_DEFAULT);
        return props;
    }

    @Test
    void defaults_enabledAndUnlimitedFile_fallbackRequestToMaxContentLength() {
        ApplicationProperties p = props("-1", "-1");
        when(p.getLong(PropertiesConstant.HTTP_MAX_CONTENT_LENGTH)).thenReturn(4L * 1024 * 1024);

        MultipartConfig cfg = MultipartConfig.fromProperties(p);
        assertTrue(cfg.isEnabled());
        assertEquals(-1L, cfg.getMaxFileSize(), "未配置 max-file-size 应不限制");
        assertEquals(4L * 1024 * 1024, cfg.getMaxRequestSize(),
                "未配置 max-request-size 应回退 server.http.max-content-length");
    }

    @Test
    void parseDataSize_units() {
        MultipartConfig cfg = MultipartConfig.fromProperties(props("10MB", "1GB"));
        assertEquals(10L * 1024 * 1024, cfg.getMaxFileSize());
        assertEquals(1024L * 1024 * 1024, cfg.getMaxRequestSize());
    }

    @Test
    void parseBareNumber_asBytes() {
        MultipartConfig cfg = MultipartConfig.fromProperties(props("2048", "4096"));
        assertEquals(2048L, cfg.getMaxFileSize());
        assertEquals(4096L, cfg.getMaxRequestSize());
    }

    @Test
    void explicitMaxRequestSize_takesPrecedenceOverMaxContentLength() {
        ApplicationProperties p = props("-1", "5MB");
        // 显式配置时不查询 max-content-length
        MultipartConfig cfg = MultipartConfig.fromProperties(p);
        assertEquals(5L * 1024 * 1024, cfg.getMaxRequestSize());
    }

    @Test
    void invalidValue_fallsBackToUnlimited() {
        MultipartConfig cfg = MultipartConfig.fromProperties(props("not-a-size", "1MB"));
        assertEquals(-1L, cfg.getMaxFileSize(), "非法值应回退不限制而非中断启动");
        assertEquals(1024L * 1024, cfg.getMaxRequestSize());
    }

    @Test
    void enabledFalse_parsed() {
        ApplicationProperties p = props("-1", "-1");
        when(p.getBoolean(PropertiesConstant.SPRING_SERVLET_MULTIPART_ENABLED,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_ENABLED_DEFAULT)).thenReturn(false);
        MultipartConfig cfg = MultipartConfig.fromProperties(p);
        assertFalse(cfg.isEnabled());
    }

    @Test
    void defaultConstant_isEnabledAndUnlimited() {
        assertTrue(MultipartConfig.DEFAULT.isEnabled());
        assertEquals(-1L, MultipartConfig.DEFAULT.getMaxFileSize());
        assertEquals(-1L, MultipartConfig.DEFAULT.getMaxRequestSize());
    }

    // ==================== file-size-threshold / location ====================

    private static ApplicationProperties propsWithThresholdAndLocation(String threshold, String location) {
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(props.get(PropertiesConstant.SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE_DEFAULT)).thenReturn("-1");
        lenient().when(props.get(PropertiesConstant.SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE_DEFAULT)).thenReturn("-1");
        lenient().when(props.getBoolean(PropertiesConstant.SPRING_SERVLET_MULTIPART_ENABLED,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_ENABLED_DEFAULT)).thenReturn(true);
        lenient().when(props.get(PropertiesConstant.SPRING_SERVLET_MULTIPART_FILE_SIZE_THRESHOLD,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_FILE_SIZE_THRESHOLD_DEFAULT)).thenReturn(threshold);
        lenient().when(props.get(PropertiesConstant.SPRING_SERVLET_MULTIPART_LOCATION,
                PropertiesConstant.SPRING_SERVLET_MULTIPART_LOCATION_DEFAULT)).thenReturn(location);
        return props;
    }

    @Test
    void threshold_defaultUnset_keepsFrameworkDefault() {
        MultipartConfig cfg = MultipartConfig.fromProperties(propsWithThresholdAndLocation("-1", ""));
        assertEquals(MultipartConfig.FILE_SIZE_THRESHOLD_UNSET, cfg.getFileSizeThreshold(),
                "未配置时应为 UNSET，由 resolver 沿用框架默认（16KB）");
        assertEquals("", cfg.getLocation(), "未配置 location 应为空");
    }

    @Test
    void threshold_dataSize_parsedToBytes() {
        MultipartConfig cfg = MultipartConfig.fromProperties(propsWithThresholdAndLocation("1MB", ""));
        assertEquals(1024L * 1024, cfg.getFileSizeThreshold());
    }

    @Test
    void threshold_zero_mapsToZero() {
        // 显式 0 对齐 Boot 的“全部落盘”语义
        MultipartConfig cfg = MultipartConfig.fromProperties(propsWithThresholdAndLocation("0", ""));
        assertEquals(0L, cfg.getFileSizeThreshold());
    }

    @Test
    void location_parsed() {
        MultipartConfig cfg = MultipartConfig.fromProperties(propsWithThresholdAndLocation("-1", "/data/upload-tmp"));
        assertEquals("/data/upload-tmp", cfg.getLocation());
    }

    @Test
    void legacyThreeArgConstructor_usesUnsetThresholdAndNoLocation() {
        MultipartConfig cfg = new MultipartConfig(true, -1L, -1L);
        assertEquals(MultipartConfig.FILE_SIZE_THRESHOLD_UNSET, cfg.getFileSizeThreshold());
        assertEquals("", cfg.getLocation());
    }

    @Test
    void nullLocation_normalizedToEmpty() {
        MultipartConfig cfg = new MultipartConfig(true, -1L, -1L, 1024L, null);
        assertEquals("", cfg.getLocation());
    }
}
