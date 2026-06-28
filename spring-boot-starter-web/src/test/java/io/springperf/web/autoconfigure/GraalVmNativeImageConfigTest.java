package io.springperf.web.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 GraalVM native-image 配置文件的正确性。
 * <p>遍历 reflect-config.json / proxy-config.json 中所有类名，
 * 确保编译时能在 classpath 上找到对应类，避免 native-image 构建时因缺失反射配置而静默失败。</p>
 */
class GraalVmNativeImageConfigTest {

    private static final String BASE_PATH = "META-INF/native-image/io.github.springperf/spring-boot-starter-web";

    private final ObjectMapper mapper = new ObjectMapper();

    // ============ reflect-config.json ============

    @Test
    void reflectConfig_jsonFormatIsValid() throws Exception {
        List<Map<String, Object>> entries = parseReflectConfig();
        assertNotNull(entries);
        assertFalse(entries.isEmpty(), "reflect-config.json should have entries");
    }

    @Test
    void reflectConfig_allClassesExist() throws Exception {
        List<Map<String, Object>> entries = parseReflectConfig();
        List<String> missing = entries.stream()
                .map(e -> (String) e.get("name"))
                .filter(name -> !classExists(name))
                .sorted()
                .toList();

        assertTrue(missing.isEmpty(),
                "Classes not found on classpath (" + missing.size() + "):\n  " +
                        String.join("\n  ", missing));
    }

    @Test
    void reflectConfig_autoConfigurationClassesExist() throws Exception {
        // 显式验证关键自动配置类
        assertClassExists("io.springperf.web.autoconfigure.SpringWebAutoConfiguration");
        assertClassExists("io.springperf.web.autoconfigure.SpringWebSupportAutoConfiguration");
        assertClassExists("io.springperf.web.autoconfigure.OpenApiAutoConfiguration");
        assertClassExists("io.springperf.web.autoconfigure.ActuatorEndpointAutoConfiguration");
    }

    // ============ proxy-config.json ============

    @Test
    void proxyConfig_jsonFormatIsValid() throws Exception {
        List<List<String>> entries = parseProxyConfig();
        assertNotNull(entries);
        assertFalse(entries.isEmpty(), "proxy-config.json should have entries");
    }

    @Test
    void proxyConfig_coreInterfacesExist() throws Exception {
        assertClassExists("io.springperf.web.context.WebComponent");
        assertClassExists("io.springperf.web.core.filter.WebFilter");
        assertClassExists("io.springperf.web.core.invoker.Invoker");
        assertClassExists("io.springperf.web.http.WebServerHttpRequest");
        assertClassExists("io.springperf.web.http.WebServerHttpResponse");
        assertClassExists("io.springperf.web.core.retval.ReturnValueResolver");
        assertClassExists("io.springperf.web.core.arg.RuntimeArgumentResolver");
        assertClassExists("io.springperf.web.core.exception.HandlerExceptionResolver");
        assertClassExists("io.springperf.web.core.codec.HttpBodyConverter");
        assertClassExists("io.springperf.web.context.LifecycleWebComponent");
    }

    // ============ resource-config.json ============

    @Test
    void resourceConfig_jsonFormatIsValid() throws Exception {
        Map<String, Object> root = parseResourceConfig();
        assertNotNull(root);
        assertTrue(root.containsKey("resources"), "resource-config.json should have 'resources' key");

        @SuppressWarnings("unchecked")
        List<Map<String, String>> resources = (List<Map<String, String>>) root.get("resources");
        assertNotNull(resources);
        assertFalse(resources.isEmpty(), "resource-config.json should have resource patterns");
    }

    @Test
    void resourceConfig_hasRequiredPatterns() throws Exception {
        Map<String, Object> root = parseResourceConfig();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> resources = (List<Map<String, String>>) root.get("resources");
        List<String> patterns = resources.stream()
                .map(r -> r.get("pattern"))
                .toList();

        assertTrue(patterns.stream().anyMatch(p -> p.contains("spring.factories")),
                "Missing spring.factories resource pattern");
        assertTrue(patterns.stream().anyMatch(p -> p.contains("AutoConfiguration.imports")),
                "Missing AutoConfiguration.imports resource pattern");
    }

    // ============ 工具方法 ============

    private List<Map<String, Object>> parseReflectConfig() throws Exception {
        try (InputStream is = loadConfig("reflect-config.json")) {
            CollectionType type = mapper.getTypeFactory()
                    .constructCollectionType(List.class,
                            mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            return mapper.readValue(is, type);
        }
    }

    @SuppressWarnings("unchecked")
    private List<List<String>> parseProxyConfig() throws Exception {
        try (InputStream is = loadConfig("proxy-config.json")) {
            return mapper.readValue(is, List.class);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResourceConfig() throws Exception {
        try (InputStream is = loadConfig("resource-config.json")) {
            return mapper.readValue(is, Map.class);
        }
    }

    private InputStream loadConfig(String fileName) {
        String path = BASE_PATH + "/" + fileName;
        InputStream is = getClass().getClassLoader().getResourceAsStream(path);
        assertNotNull(is, "Native-image config file not found: " + path);
        return is;
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, GraalVmNativeImageConfigTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static void assertClassExists(String className) {
        assertTrue(classExists(className), "Required class not found: " + className);
    }
}
