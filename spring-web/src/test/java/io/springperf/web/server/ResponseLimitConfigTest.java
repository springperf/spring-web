package io.springperf.web.server;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;

class ResponseLimitConfigTest {

    @Test
    void defaults_matchBoot() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.getLong(PropertiesConstant.MAX_SWALLOW_SIZE))
                .thenReturn(PropertiesConstant.MAX_SWALLOW_SIZE_DEFAULT);
        when(props.getInt(PropertiesConstant.MAX_HTTP_RESPONSE_HEADER_SIZE))
                .thenReturn(PropertiesConstant.MAX_HTTP_RESPONSE_HEADER_SIZE_DEFAULT);

        ResponseLimitConfig cfg = ResponseLimitConfig.fromProperties(props);
        assertEquals(PropertiesConstant.MAX_SWALLOW_SIZE_DEFAULT, cfg.getMaxSwallowSize());
        assertEquals(PropertiesConstant.MAX_HTTP_RESPONSE_HEADER_SIZE_DEFAULT, cfg.getMaxResponseHeaderSize());
    }

    @Test
    void fromProperties_overridesApplied() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.getLong(PropertiesConstant.MAX_SWALLOW_SIZE)).thenReturn(1024L);
        when(props.getInt(PropertiesConstant.MAX_HTTP_RESPONSE_HEADER_SIZE)).thenReturn(256);

        ResponseLimitConfig cfg = ResponseLimitConfig.fromProperties(props);
        assertEquals(1024L, cfg.getMaxSwallowSize());
        assertEquals(256, cfg.getMaxResponseHeaderSize());
    }

    @Test
    void shouldCloseAfterError_negativeLimitNeverCloses() {
        ResponseLimitConfig cfg = new ResponseLimitConfig(-1, 8192);
        assertFalse(cfg.shouldCloseAfterError(1_000_000L));
    }

    @Test
    void shouldCloseAfterError_zeroLimitAlwaysClosesOnBody() {
        ResponseLimitConfig cfg = new ResponseLimitConfig(0, 8192);
        assertTrue(cfg.shouldCloseAfterError(1L));
        assertFalse(cfg.shouldCloseAfterError(0L));
    }

    @Test
    void shouldCloseAfterError_positiveLimitComparesBody() {
        ResponseLimitConfig cfg = new ResponseLimitConfig(100, 8192);
        assertTrue(cfg.shouldCloseAfterError(101L));
        assertFalse(cfg.shouldCloseAfterError(100L));
        assertFalse(cfg.shouldCloseAfterError(50L));
    }
}
