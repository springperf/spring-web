package io.springperf.web.server;

import io.springperf.web.context.ApplicationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link CompressionConfig} 解析单元测试：覆盖关闭默认态、启用后 mime/min-size/excluded 解析、
 * 默认阈值回退、非法 min-size fail-fast。
 */
public class CompressionConfigTest {

    private ApplicationProperties propsWith(String... kv) {
        MockEnvironment env = new MockEnvironment();
        for (String s : kv) {
            int idx = s.indexOf('=');
            env.setProperty(s.substring(0, idx), s.substring(idx + 1));
        }
        ApplicationProperties p = new ApplicationProperties();
        p.setEnvironment(env);
        return p;
    }

    @Test
    void disabledByDefault() {
        CompressionConfig c = CompressionConfig.fromProperties(propsWith());
        assertFalse(c.isEnabled());
    }

    @Test
    void enabledParsesMimeMinSizeAndExcluded() {
        CompressionConfig c = CompressionConfig.fromProperties(propsWith(
                "server.compression.enabled=true",
                "server.compression.mime-types=application/json, text/html",
                "server.compression.min-response-size=1MB",
                "server.compression.excluded-user-agents=test-agent, bot"));
        assertTrue(c.isEnabled());
        assertEquals(1024L * 1024, c.getMinResponseSizeBytes());
        assertTrue(c.getMimeTypes().contains("application/json"));
        assertTrue(c.getMimeTypes().contains("text/html"));
        assertEquals(2, c.getExcludedUserAgents().size());
    }

    @Test
    void minSizeDefaultsTo2KBWhenEmpty() {
        CompressionConfig c = CompressionConfig.fromProperties(
                propsWith("server.compression.enabled=true"));
        assertTrue(c.isEnabled());
        assertEquals(2048L, c.getMinResponseSizeBytes());
    }

    @Test
    void invalidMinSizeThrows() {
        assertThrows(IllegalStateException.class, () -> CompressionConfig.fromProperties(
                propsWith("server.compression.enabled=true", "server.compression.min-response-size=abc")));
    }
}
