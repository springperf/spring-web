package io.springperf.web.autoconfigure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.springperf.web.view.ViewProperties;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁死「配置键单一真源」：ViewProperties 中每个 spring.* 常量都必须在 IDE 元数据里登记，
 * 关键默认值也必须与常量一致，避免手写 JSON 随代码演进漂移。
 */
class ViewConfigurationMetadataTest {

    private static final String RESOURCE = "META-INF/additional-spring-configuration-metadata.json";

    @Test
    void everyViewPropertyKey_hasMetadataEntry() throws Exception {
        Set<String> documented = new HashSet<>();
        for (JsonNode node : properties()) {
            documented.add(node.get("name").asText());
        }

        Set<String> missing = new HashSet<>();
        for (Field field : ViewProperties.class.getDeclaredFields()) {
            if (!Modifier.isPublic(field.getModifiers()) || !Modifier.isStatic(field.getModifiers())
                    || field.getType() != String.class) {
                continue;
            }
            String value = (String) field.get(null);
            if (value != null && value.startsWith("spring.") && !documented.contains(value)) {
                missing.add(value);
            }
        }
        assertTrue(missing.isEmpty(), "以下配置键缺少 IDE 元数据: " + missing);
    }

    @Test
    void metadataDefaults_matchViewProperties() throws Exception {
        JsonNode properties = properties();
        assertEquals(ViewProperties.ENGINE_DEFAULT, defaultValue(properties, ViewProperties.ENGINE));
        assertEquals(ViewProperties.THYMELEAF_MODE_DEFAULT, defaultValue(properties, ViewProperties.THYMELEAF_MODE));
        assertEquals(ViewProperties.FREEMARKER_CONTENT_TYPE_DEFAULT,
                defaultValue(properties, ViewProperties.FREEMARKER_CONTENT_TYPE));
    }

    private static String defaultValue(JsonNode properties, String name) {
        for (JsonNode node : properties) {
            if (name.equals(node.get("name").asText())) {
                JsonNode value = node.get("defaultValue");
                return value == null ? null : value.asText();
            }
        }
        return null;
    }

    private static JsonNode properties() throws Exception {
        try (InputStream in = ViewConfigurationMetadataTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(in, RESOURCE + " 不在测试 classpath");
            JsonNode root = new ObjectMapper().readTree(in);
            JsonNode properties = root.get("properties");
            assertNotNull(properties, RESOURCE + " 缺少 properties 数组");
            return properties;
        }
    }
}
