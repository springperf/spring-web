package io.springperf.web.support.servlet.context;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EncodingConfig} 单元测试：{@code server.servlet.encoding.*} 解析与
 * {@code force-request}/{@code force-response} 对 {@code force} 的继承。
 */
class EncodingConfigTest {

    private static ApplicationProperties props(String charset, Boolean force,
                                               Boolean forceRequest, Boolean forceResponse) {
        ApplicationProperties props = mock(ApplicationProperties.class);
        lenient().when(props.get(PropertiesConstant.SERVLET_ENCODING_CHARSET,
                        PropertiesConstant.SERVLET_ENCODING_CHARSET_DEFAULT))
                .thenReturn(charset != null ? charset : PropertiesConstant.SERVLET_ENCODING_CHARSET_DEFAULT);
        boolean forceValue = force != null && force;
        lenient().when(props.getBoolean(PropertiesConstant.SERVLET_ENCODING_FORCE,
                        PropertiesConstant.SERVLET_ENCODING_FORCE_DEFAULT))
                .thenReturn(forceValue);
        // 注意：代码以 getBoolean(FORCE_REQUEST, force) 读取，故桩的默认参数须与 force 一致
        lenient().when(props.getBoolean(PropertiesConstant.SERVLET_ENCODING_FORCE_REQUEST, forceValue))
                .thenReturn(forceRequest != null ? forceRequest : forceValue);
        lenient().when(props.getBoolean(PropertiesConstant.SERVLET_ENCODING_FORCE_RESPONSE, forceValue))
                .thenReturn(forceResponse != null ? forceResponse : forceValue);
        return props;
    }

    @Test
    void default_utf8_noForce() {
        EncodingConfig cfg = EncodingConfig.fromProperties(props(null, null, null, null));
        assertEquals("UTF-8", cfg.getCharset());
        assertFalse(cfg.isForceRequest());
        assertFalse(cfg.isForceResponse());
    }

    @Test
    void charset_fromProperties() {
        EncodingConfig cfg = EncodingConfig.fromProperties(props("GBK", null, null, null));
        assertEquals("GBK", cfg.getCharset());
    }

    @Test
    void force_inheritedByRequestAndResponse() {
        // force=true，force-request/force-response 未配置 → 均继承 true
        EncodingConfig cfg = EncodingConfig.fromProperties(props("UTF-8", true, null, null));
        assertTrue(cfg.isForceRequest());
        assertTrue(cfg.isForceResponse());
    }

    @Test
    void forceRequest_explicitOverridesForce() {
        // force=true 但 force-request 显式 false → 请求不强制、响应强制
        EncodingConfig cfg = EncodingConfig.fromProperties(props("UTF-8", true, false, null));
        assertFalse(cfg.isForceRequest());
        assertTrue(cfg.isForceResponse());
    }

    @Test
    void forceResponse_explicitOverridesForce() {
        EncodingConfig cfg = EncodingConfig.fromProperties(props("UTF-8", true, null, false));
        assertTrue(cfg.isForceRequest());
        assertFalse(cfg.isForceResponse());
    }

    @Test
    void defaultConstant_noForce() {
        assertEquals("UTF-8", EncodingConfig.DEFAULT.getCharset());
        assertFalse(EncodingConfig.DEFAULT.isForceRequest());
        assertFalse(EncodingConfig.DEFAULT.isForceResponse());
    }
}
