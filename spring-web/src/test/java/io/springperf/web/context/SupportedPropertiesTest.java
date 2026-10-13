package io.springperf.web.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 配置键一致性校验（P4）：确保 {@link PropertiesConstant} / {@link ViewProperties} 中声明的
 * 每一个配置键都在 {@code additional-spring-configuration-metadata.json} 中登记。
 *
 * <p>目的：把「文档/元数据滞后」变成<b>编译期可发现的测试失败</b>——新增配置键却忘记登记元数据时，
 * 本测试立即失败并列出缺失键，避免再次出现「代码已实现、文档仍标未做」的漂移。</p>
 *
 * <p>说明：元数据中允许存在<b>非 PropertiesConstant 来源</b>的键（如视图引擎键由
 * {@code ViewProperties} 声明、或 starter 模块自有键），故此处只做「代码键 ⊆ 元数据键」的单向断言。</p>
 */
class SupportedPropertiesTest {

    /** 匹配形如 {@code server.xxx} / {@code spring.xxx} / {@code pool.xxx} 的配置键（含 {@code .} 且小写开头）。 */
    private static boolean isPropertyKey(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (value.indexOf('.') < 0) {
            return false;
        }
        char first = value.charAt(0);
        if (!Character.isLowerCase(first)) {
            return false;
        }
        // 排除前缀常量（以 '.' 结尾，如 server.servlet.context-parameters.）
        return !value.endsWith(".");
    }

    /** 反射收集指定类中所有「值形如配置键」的 public static final String 常量。 */
    private static Set<String> collectKeys(Class<?> clazz) {
        Set<String> keys = new TreeSet<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getType() != String.class) {
                continue;
            }
            int mod = field.getModifiers();
            if (!Modifier.isStatic(mod) || !Modifier.isFinal(mod) || !Modifier.isPublic(mod)) {
                continue;
            }
            try {
                String value = (String) field.get(null);
                if (isPropertyKey(value)) {
                    keys.add(value);
                }
            } catch (IllegalAccessException ignored) {
                // public static final 不应触发；忽略
            }
        }
        return keys;
    }

    /** 读取指定模块的 additional-spring-configuration-metadata.json 中已登记的键集合。 */
    private static Set<String> readMetadataKeys(Class<?> anchorClass) throws Exception {
        Set<String> names = new TreeSet<>();
        String resource = "/META-INF/additional-spring-configuration-metadata.json";
        try (InputStream in = anchorClass.getResourceAsStream(resource)) {
            if (in == null) {
                return names;
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(in);
            JsonNode props = root.get("properties");
            if (props != null && props.isArray()) {
                for (JsonNode node : props) {
                    JsonNode name = node.get("name");
                    if (name != null && !name.asText().isEmpty()) {
                        names.add(name.asText());
                    }
                }
            }
        }
        return names;
    }

    @Test
    void allPropertiesConstantKeys_areRegisteredInMetadata() throws Exception {
        Set<String> codeKeys = collectKeys(PropertiesConstant.class);
        assertTrue(codeKeys.size() > 50, "应收集到大量配置键，实际 " + codeKeys.size());

        Set<String> metadataKeys = readMetadataKeys(PropertiesConstant.class);

        Set<String> missing = new TreeSet<>(codeKeys);
        missing.removeAll(metadataKeys);

        if (!missing.isEmpty()) {
            fail("以下配置键已在 PropertiesConstant 声明但未登记到 "
                    + "spring-web/src/main/resources/META-INF/additional-spring-configuration-metadata.json：\n  "
                    + String.join("\n  ", missing)
                    + "\n（请补充元数据，保持文档与代码一致）");
        }
    }

    @Test
    void collectKeys_recognizesPropertyShapes() {
        Set<String> keys = collectKeys(PropertiesConstant.class);
        assertTrue(keys.contains("server.port"), "应识别 server.port");
        assertTrue(keys.contains("spring.web.locale"), "应识别 spring.web.locale");
        assertTrue(keys.contains("pool.core-pool-size"), "应识别 pool.core-pool-size");
        // 前缀常量（以 . 结尾）不应被当作键
        assertTrue(keys.stream().noneMatch(k -> k.endsWith(".")), "不应包含前缀常量");
        // *_DEFAULT 常量值多为非键形态（如 "never"、"8080"），不应混入
        assertTrue(keys.stream().noneMatch(k -> k.startsWith("never")), "不应包含默认值常量");
    }

    @Test
    void metadataJson_isLoadableAndNonEmpty() throws Exception {
        Set<String> metadataKeys = readMetadataKeys(PropertiesConstant.class);
        assertTrue(metadataKeys.contains("server.port"), "元数据应至少包含 server.port");
        List<String> sample = List.copyOf(metadataKeys).subList(0, Math.min(3, metadataKeys.size()));
        assertTrue(!sample.isEmpty());
    }

    /**
     * 输出「已支持配置键」完整清单（按前缀分组），便于人工核对与生成文档。
     * 不写文件、无副作用；需要落盘时运行 mvn 时加 {@code -Dperf.props.dump=<path>}。
     */
    @Test
    void dumpSupportedProperties() throws Exception {
        Set<String> codeKeys = collectKeys(PropertiesConstant.class);
        Set<String> metadataKeys = readMetadataKeys(PropertiesConstant.class);

        StringBuilder sb = new StringBuilder();
        sb.append("\n===== Supported properties (").append(codeKeys.size()).append(") =====\n");
        for (String key : codeKeys) {
            sb.append("  ").append(key).append('\n');
        }
        sb.append("===== Metadata-only keys (not declared in PropertiesConstant) =====\n");
        Set<String> metadataOnly = new TreeSet<>(metadataKeys);
        metadataOnly.removeAll(codeKeys);
        for (String key : metadataOnly) {
            sb.append("  ").append(key).append('\n');
        }
        System.out.println(sb);

        String dumpPath = System.getProperty("perf.props.dump");
        if (dumpPath != null && !dumpPath.isEmpty()) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(dumpPath), sb.toString());
        }
    }
}
