package io.springperf.web.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 GraalVM native-image 配置文件的正确性。
 * <p>
 * 不只验「文件存在」：配置文件里的类名、代理接口、资源正则都必须真实可达/可用，
 * 且 {@code AutoConfiguration.imports} 中登记的自动配置类必须全部能在 classpath 上加载。
 * </p>
 */
class GraalVmNativeImageConfigTest {

    private static final String BASE_PATH = "META-INF/native-image/io.github.springperf/spring-boot-starter-web";

    /** 既作 classpath 资源名，也作 resource-config 正则要覆盖的真实路径。 */
    private static final String IMPORTS_PATH =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

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
        List<String> missing = entries.stream().map(e -> (String) e.get("name")).filter(name -> !classExists(name))
                .sorted().toList();

        assertTrue(missing.isEmpty(),
                "Classes not found on classpath (" + missing.size() + "):\n  " + String.join("\n  ", missing));
    }

    /** 此前只硬编码 5 个类名，与 .imports 内容脱钩；改为逐条校验注册项。 */
    @Test
    void imports_allRegisteredClassesExist() throws Exception {
        List<String> missing = imports().stream().filter(name -> !classExists(name)).toList();
        assertTrue(missing.isEmpty(), "AutoConfiguration.imports 存在不可达类: " + missing);
    }

    // ============ proxy-config.json ============

    @Test
    void proxyConfig_jsonFormatIsValid() throws Exception {
        List<List<String>> entries = parseProxyConfig();
        assertNotNull(entries);
        assertFalse(entries.isEmpty(), "proxy-config.json should have entries");
    }

    /** 此前只验硬编码的 10 个接口，JSON 里写错类名依然全绿。 */
    @Test
    void proxyConfig_allListedInterfacesExist() throws Exception {
        List<List<String>> entries = parseProxyConfig();
        List<String> coreInterfaces = List.of("io.springperf.web.context.WebComponent",
                "io.springperf.web.core.filter.WebFilter", "io.springperf.web.core.invoker.Invoker",
                "io.springperf.web.http.WebServerHttpRequest", "io.springperf.web.http.WebServerHttpResponse",
                "io.springperf.web.core.retval.ReturnValueResolver", "io.springperf.web.context.LifecycleWebComponent");
        List<String> documented = entries.stream().flatMap(List::stream).toList();

        for (List<String> entry : entries) {
            assertFalse(entry.isEmpty(), "proxy-config.json 存在空的代理接口数组");
        }
        List<String> missing = documented.stream().filter(name -> !classExists(name)).distinct().toList();
        assertTrue(missing.isEmpty(), "proxy-config.json 存在不可达接口: " + missing);
        assertTrue(documented.containsAll(coreInterfaces), "proxy-config.json 缺少核心接口: " + coreInterfaces);
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

    /** 此前只做 String.contains，正则写坏仍通过；现在逐条编译并实测能覆盖 .imports 资源。 */
    @Test
    void resourceConfig_patternsAreValidAndCoverImports() throws Exception {
        Map<String, Object> root = parseResourceConfig();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> resources = (List<Map<String, String>>) root.get("resources");
        List<String> patterns = resources.stream().map(r -> r.get("pattern")).toList();

        assertFalse(patterns.stream().anyMatch(p -> p == null || p.isEmpty()), "存在空 pattern");
        for (String pattern : patterns) {
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                fail("resource-config.json 存在非法正则: " + pattern + " -> " + e.getDescription());
            }
        }
        assertTrue(patterns.stream().anyMatch(p -> p.contains("spring.factories")),
                "Missing spring.factories resource pattern");
        assertTrue(patterns.stream().anyMatch(p -> Pattern.compile(p).matcher(IMPORTS_PATH).matches()),
                "没有 pattern 能匹配 " + IMPORTS_PATH);
    }

    // ============ 工具方法 ============

    private List<Map<String, Object>> parseReflectConfig() throws Exception {
        try (InputStream is = loadConfig("reflect-config.json")) {
            CollectionType type = mapper.getTypeFactory().constructCollectionType(List.class,
                    mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            return mapper.readValue(is, type);
        }
    }

    private List<List<String>> parseProxyConfig() throws Exception {
        try (InputStream is = loadConfig("proxy-config.json")) {
            CollectionType interfaces = mapper.getTypeFactory().constructCollectionType(List.class, String.class);
            CollectionType type = mapper.getTypeFactory().constructCollectionType(List.class, interfaces);
            return mapper.readValue(is, type);
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

    private List<String> imports() throws Exception {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(IMPORTS_PATH)) {
            assertNotNull(is, IMPORTS_PATH + " 不在 classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        }
    }

    private static boolean classExists(String className) {
        try {
            Class.forName(className, false, GraalVmNativeImageConfigTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
