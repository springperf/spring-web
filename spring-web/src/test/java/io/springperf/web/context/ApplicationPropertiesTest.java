package io.springperf.web.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

class ApplicationPropertiesTest {

    // ==================== clearCache（配置动态刷新） ====================

    /**
     * 创建基于可变 Map 的 Environment mock：测试中途改 Map 即可模拟配置中心推送。 返回的 props 通过 {@link ApplicationProperties#clearCache()} 重新从
     * Environment 读取。
     */
    private ApplicationProperties createWithMutableEnv(Map<String, String> values) {
        Environment env = mock(Environment.class);
        when(env.getProperty(anyString())).thenAnswer(inv -> values.get(inv.getArgument(0, String.class)));
        when(env.getProperty(anyString(), anyString())).thenAnswer(
                inv -> values.getOrDefault(inv.getArgument(0, String.class), inv.getArgument(1, String.class)));
        ApplicationProperties props = new ApplicationProperties();
        props.setEnvironment(env);
        return props;
    }

    // ==================== 热路径字段快照（getMaxParameterCount / getHttpTimeoutMillis） ====================

    @Test
    void hotField_maxParameterCount_resolvesThenReflectsRefresh() {
        Map<String, String> values = new HashMap<>();
        values.put(PropertiesConstant.SERVER_MAX_PARAMETER_COUNT, "100");
        ApplicationProperties props = createWithMutableEnv(values);

        assertEquals(100, props.getMaxParameterCount(), "首次访问应解析 Environment");
        assertEquals(100, props.getMaxParameterCount(), "二次访问应命中字段快照");

        values.put(PropertiesConstant.SERVER_MAX_PARAMETER_COUNT, "200");
        assertEquals(100, props.getMaxParameterCount(), "清缓存前返回旧快照");
        props.clearCache();
        assertEquals(200, props.getMaxParameterCount(), "清缓存后字段快照应基于新配置重新解析");
    }

    @Test
    void hotField_httpTimeoutMillis_resolvesThenReflectsRefresh() {
        Map<String, String> values = new HashMap<>();
        values.put(PropertiesConstant.HTTP_TIMEOUT, "30s");
        ApplicationProperties props = createWithMutableEnv(values);

        assertEquals(30000L, props.getHttpTimeoutMillis(), "Duration 语义（裸后缀）应解析为毫秒");

        values.put(PropertiesConstant.HTTP_TIMEOUT, "1m");
        props.clearCache();
        assertEquals(60000L, props.getHttpTimeoutMillis(), "刷新后应重新解析");
    }

    @Test
    void hotField_unconfigured_fallsBackToConstantDefault() {
        ApplicationProperties props = createWithMutableEnv(new HashMap<>());
        assertEquals(PropertiesConstant.getDefault(PropertiesConstant.SERVER_MAX_PARAMETER_COUNT),
                props.getMaxParameterCount(), "未配置应回退 PropertiesConstant 默认值");
        assertEquals(PropertiesConstant.HTTP_TIMEOUT_DEFAULT, props.getHttpTimeoutMillis(), "未配置应回退注册默认值");
        assertEquals(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE_DEFAULT, props.getMaxInMemorySize(),
                "未配置应回退 PropertiesConstant 默认值");
    }

    @Test
    void hotField_maxInMemorySize_resolvesAtConstructionThenReflectsRefresh() {
        Map<String, String> values = new HashMap<>();
        values.put(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE, "8192");
        ApplicationProperties props = createWithMutableEnv(values);

        assertEquals(8192, props.getMaxInMemorySize(), "构造期应已急切解析");

        values.put(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE, "16384");
        assertEquals(8192, props.getMaxInMemorySize(), "清缓存前为旧快照");
        props.clearCache();
        assertEquals(16384, props.getMaxInMemorySize(), "清缓存后应重解析");
    }

    @Test
    void clearCache_longValue_reflectsUpdatedEnvironment() {
        Map<String, String> values = new HashMap<>();
        values.put(PropertiesConstant.POOL_CORE_POOL_SIZE, "10");
        ApplicationProperties props = createWithMutableEnv(values);

        assertEquals(10, props.getInt(PropertiesConstant.POOL_CORE_POOL_SIZE));

        values.put(PropertiesConstant.POOL_CORE_POOL_SIZE, "20");
        // 未清缓存：仍返回旧值
        assertEquals(10, props.getInt(PropertiesConstant.POOL_CORE_POOL_SIZE), "清空前应返回缓存旧值");

        props.clearCache();
        assertEquals(20, props.getInt(PropertiesConstant.POOL_CORE_POOL_SIZE), "清空后应读取 Environment 最新值");
    }

    @Test
    void clearCache_stringValue_reflectsUpdatedEnvironment() {
        Map<String, String> values = new HashMap<>();
        values.put(PropertiesConstant.CONTEXT_PATH, "/old");
        ApplicationProperties props = createWithMutableEnv(values);

        assertEquals("/old", props.get(PropertiesConstant.CONTEXT_PATH, "/"));

        values.put(PropertiesConstant.CONTEXT_PATH, "/new");
        assertEquals("/old", props.get(PropertiesConstant.CONTEXT_PATH, "/"));

        props.clearCache();
        assertEquals("/new", props.get(PropertiesConstant.CONTEXT_PATH, "/"));
    }

    @Test
    void clearCache_booleanValue_reflectsUpdatedEnvironment() {
        Map<String, String> values = new HashMap<>();
        values.put(PropertiesConstant.SERVER_NETTY_TCP_NODELAY, "true");
        ApplicationProperties props = createWithMutableEnv(values);

        assertTrue(props.getBoolean(PropertiesConstant.SERVER_NETTY_TCP_NODELAY, false));

        values.put(PropertiesConstant.SERVER_NETTY_TCP_NODELAY, "false");
        props.clearCache();

        assertFalse(props.getBoolean(PropertiesConstant.SERVER_NETTY_TCP_NODELAY, true));
    }

    @Test
    void clearCache_keyRemoved_fallsBackToDefault() {
        Map<String, String> values = new HashMap<>();
        values.put(PropertiesConstant.SERVER_PORT, "9090");
        ApplicationProperties props = createWithMutableEnv(values);
        assertEquals(9090, props.getInt(PropertiesConstant.SERVER_PORT));

        values.remove(PropertiesConstant.SERVER_PORT);
        props.clearCache();

        assertEquals(PropertiesConstant.SERVER_PORT_DEFAULT, props.getInt(PropertiesConstant.SERVER_PORT),
                "键被移除后应回退到默认值");
    }

    @Test
    void clearCache_isIdempotent() {
        Map<String, String> values = new HashMap<>();
        values.put(PropertiesConstant.SERVER_PORT, "8081");
        ApplicationProperties props = createWithMutableEnv(values);

        props.clearCache();
        props.clearCache();
        assertEquals(8081, props.getInt(PropertiesConstant.SERVER_PORT));
    }

    private ApplicationProperties createProperties(String key, String value) {
        ApplicationProperties props = new ApplicationProperties();
        Environment env = mock(Environment.class);
        // 1-arg getProperty(key) — 用于 getLong/getInt 内部
        when(env.getProperty(anyString())).thenAnswer(invocation -> {
            String k = invocation.getArgument(0);
            return k.equals(key) ? value : null;
        });
        // 2-arg getProperty(key, defaultValue) — 用于 get(key, defaultValue)
        // 注意：defaultValue 可能为 null（如 getDurationMillis 传 null），须用 nullable 而非 anyString
        when(env.getProperty(anyString(), nullable(String.class))).thenAnswer(invocation -> {
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
    void get_usingHttpMaxContentLengthConstant_default_returns4194304() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertEquals(4 * 1024 * 1024, props.getInt(PropertiesConstant.HTTP_MAX_CONTENT_LENGTH));
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
    void get_missingKey_nullDefault_notCached() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertNull(props.get("missing.key", null));
        assertNull(props.get("missing.key", null), "null 值不缓存，每次重查 Environment");
    }

    @Test
    void getLong_readTimeoutDefault_returns30000() {
        ApplicationProperties props = createProperties("nonexistent", null);
        assertEquals(30000L, props.getLong(PropertiesConstant.HTTP_READ_TIMEOUT), "read-timeout 默认 30s 应生效");
    }

    // ==================== Duration 统一解析 ====================

    @Test
    void getDurationMillis_bareNumber_treatedAsMillis() {
        ApplicationProperties props = createProperties("d.key", "5000");
        assertEquals(5000L, props.getDurationMillis("d.key", 1L));
    }

    @Test
    void getDurationMillis_suffixes() {
        assertEquals(30_000L, createProperties("d", "30s").getDurationMillis("d", 1L));
        assertEquals(60_000L, createProperties("d", "1m").getDurationMillis("d", 1L));
        assertEquals(3_600_000L, createProperties("d", "1h").getDurationMillis("d", 1L));
        assertEquals(86_400_000L, createProperties("d", "1d").getDurationMillis("d", 1L));
    }

    @Test
    void getDurationMillis_iso8601() {
        assertEquals(90_000L, createProperties("d", "PT90S").getDurationMillis("d", 1L));
    }

    @Test
    void getDurationMillis_missing_returnsDefault() {
        assertEquals(1234L, createProperties("nonexistent", null).getDurationMillis("d", 1234L));
    }

    @Test
    void getDurationMillis_invalidUnit_throws() {
        ApplicationProperties props = createProperties("d", "10x");
        assertThrows(IllegalArgumentException.class, () -> props.getDurationMillis("d", 1L));
    }

    @Test
    void getDurationSeconds_bareNumber_treatedAsSeconds() {
        ApplicationProperties props = createProperties("d", "90");
        assertEquals(90L, props.getDurationSeconds("d", 1L));
    }

    @Test
    void getDurationSeconds_suffixes() {
        assertEquals(1800L, createProperties("d", "30m").getDurationSeconds("d", 1L));
        assertEquals(3600L, createProperties("d", "1h").getDurationSeconds("d", 1L));
        assertEquals(86_400L, createProperties("d", "1d").getDurationSeconds("d", 1L));
    }

    @Test
    void getDurationSeconds_missing_returnsDefault() {
        assertEquals(1800L, createProperties("nonexistent", null).getDurationSeconds("d", 1800L));
    }

    @Test
    void getDurationMillis_cached_reflectsEnvironmentOnlyAfterClear() {
        // 缓存生效：解析结果固化，环境变更后仍返回旧值，clearCache 后才读到新值
        Map<String, String> values = new HashMap<>();
        values.put("d.key", "30s");
        ApplicationProperties props = createWithMutableEnv(values);

        assertEquals(30_000L, props.getDurationMillis("d.key", 1L));

        values.put("d.key", "1m");
        assertEquals(30_000L, props.getDurationMillis("d.key", 1L), "清空前应返回缓存旧解析值");

        props.clearCache();
        assertEquals(60_000L, props.getDurationMillis("d.key", 1L), "清空后应重新解析");
    }

    @Test
    void getDurationMillis_unconfigured_sentinel_keepsCallerDefaults() {
        // 未配置键缓存「未配置」哨兵：同一键不同调用方默认值各自生效，且不再反复查 Environment
        ApplicationProperties props = createProperties("nonexistent", null);
        assertEquals(1000L, props.getDurationMillis("d", 1000L));
        assertEquals(2000L, props.getDurationMillis("d", 2000L), "哨兵命中时应回退本次调用方默认值");
    }

    @Test
    void getDurationSeconds_cached_reflectsEnvironmentOnlyAfterClear() {
        Map<String, String> values = new HashMap<>();
        values.put("d.key", "1m");
        ApplicationProperties props = createWithMutableEnv(values);

        assertEquals(60L, props.getDurationSeconds("d.key", 1L));

        values.put("d.key", "2m");
        assertEquals(60L, props.getDurationSeconds("d.key", 1L), "清空前应返回缓存旧解析值");

        props.clearCache();
        assertEquals(120L, props.getDurationSeconds("d.key", 1L), "清空后应重新解析");
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
