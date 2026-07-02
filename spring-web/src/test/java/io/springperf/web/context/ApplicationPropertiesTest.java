package io.springperf.web.context;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApplicationPropertiesTest {

    private ApplicationProperties createProperties(String key, String value) {
        ApplicationProperties props = new ApplicationProperties();
        Environment env = mock(Environment.class);
        // 1-arg getProperty(key) — 用于 getLong/getInt 内部
        when(env.getProperty(anyString())).thenAnswer(invocation -> {
            String k = invocation.getArgument(0);
            return k.equals(key) ? value : null;
        });
        // 2-arg getProperty(key, defaultValue) — 用于 get(key, defaultValue)
        when(env.getProperty(anyString(), anyString())).thenAnswer(invocation -> {
            String k = invocation.getArgument(0);
            String def = invocation.getArgument(1);
            return k.equals(key) ? value : def;
        });
        props.setEnvironment(env);
        return props;
    }

    @Test
    void get_usingServerPortConstant_returns8080() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertEquals(8080, props.getInt(PropertiesConstant.SERVER_PORT));
    }

    @Test
    void get_usingServerPortConstant_custom_returnsConfigured() {
        ApplicationProperties props = createProperties(PropertiesConstant.SERVER_PORT, "9090");
        assertEquals(9090, props.getInt(PropertiesConstant.SERVER_PORT));
    }

    @Test
    void get_usingContextPathConstant_default_returnsDefault() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertEquals("/", props.get(PropertiesConstant.CONTEXT_PATH, "/"));
    }

    @Test
    void get_usingContextPathConstant_custom_returnsValue() {
        ApplicationProperties props = createProperties(PropertiesConstant.CONTEXT_PATH, "/api");
        assertEquals("/api", props.get(PropertiesConstant.CONTEXT_PATH, "/"));
    }

    @Test
    void get_usingHttpMaxContentLengthConstant_default_returns1048576() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertEquals(1048576, props.getInt(PropertiesConstant.HTTP_MAX_CONTENT_LENGTH));
    }

    @Test
    void get_usingHttpMaxContentLengthConstant_custom_returnsConfigured() {
        ApplicationProperties props = createProperties(PropertiesConstant.HTTP_MAX_CONTENT_LENGTH, "2097152");
        assertEquals(2097152, props.getInt(PropertiesConstant.HTTP_MAX_CONTENT_LENGTH));
    }

    @Test
    void get_usingHttpTimeoutConstant_default_returns60000() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertEquals(60000, props.getLong(PropertiesConstant.HTTP_TIMEOUT));
    }

    @Test
    void get_usingHttpTimeoutConstant_custom_returnsConfigured() {
        ApplicationProperties props = createProperties(PropertiesConstant.HTTP_TIMEOUT, "30000");
        assertEquals(30000, props.getLong(PropertiesConstant.HTTP_TIMEOUT));
    }

    @Test
    void get_usingCheckOnStartupConstant_default_returnsTrue() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertTrue(props.getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true));
    }

    @Test
    void get_usingCheckOnStartupConstant_false_returnsFalse() {
        ApplicationProperties props = createProperties(PropertiesConstant.CHECK_ON_STARTUP, "false");
        assertFalse(props.getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true));
    }

    @Test
    void get_customKey_returnsValue() {
        ApplicationProperties props = createProperties("custom.key", "customValue");
        assertEquals("customValue", props.get("custom.key", "default"));
    }

    @Test
    void get_missingKey_returnsDefault() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertEquals("default", props.get("missing.key", "default"));
    }

    @Test
    void getInt_custom_returnsParsed() {
        ApplicationProperties props = createProperties("int.key", "42");
        assertEquals(42, props.getInt("int.key"));
    }

    @Test
    void getInt_missing_returnsDefault() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertEquals(0, props.getInt("missing"));
    }

    @Test
    void getBoolean_custom_returnsParsed() {
        ApplicationProperties props = createProperties("bool.key", "true");
        assertTrue(props.getBoolean("bool.key", false));
    }

    @Test
    void getBoolean_missing_returnsDefault() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertTrue(props.getBoolean("missing", true));
    }

    @Test
    void getLong_custom_returnsParsed() {
        ApplicationProperties props = createProperties("long.key", "10000000000");
        assertEquals(10000000000L, props.getLong("long.key"));
    }

    @Test
    void getLong_missing_returnsDefault() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertEquals(0L, props.getLong("missing"));
    }

    @Test
    void getDouble_custom_returnsParsed() {
        ApplicationProperties props = createProperties("double.key", "3.14");
        assertEquals(3.14, props.getDouble("double.key", 0.0), 0.001);
    }

    @Test
    void getDouble_missing_returnsDefault() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertEquals(2.5, props.getDouble("missing", 2.5), 0.001);
    }
}
