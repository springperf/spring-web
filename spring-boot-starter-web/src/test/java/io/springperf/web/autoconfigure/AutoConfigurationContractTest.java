package io.springperf.web.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.SimpleMetadataReaderFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定自动配置注册契约：{@code AutoConfiguration.imports} 的条目、注解规范与显式排序。
 * <p>
 * 历史上出现过 .imports 残留已删除类名导致启动失败（见 CHANGELOG），而此前没有任何测试读取该文件。
 * </p>
 */
class AutoConfigurationContractTest {

    private static final String IMPORTS_RESOURCE =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private static final String PREFIX = "io.springperf.web.autoconfigure.";

    /** 唯一真源：新增/删除自动配置必须同步更新本集合与 .imports。 */
    private static final Set<String> EXPECTED = Set.of(
            PREFIX + "SpringWebAutoConfiguration",
            PREFIX + "SpringWebRuntimeHintsConfiguration",
            PREFIX + "WebServerInitializedEventAutoConfiguration",
            PREFIX + "SpringWebServletAutoConfiguration",
            PREFIX + "SpringWebMvcSupportAutoConfiguration",
            PREFIX + "ActuatorEndpointAutoConfiguration",
            PREFIX + "SpringDataWebCompatibilityAutoConfiguration",
            PREFIX + "SpringBootAdminClientAutoConfiguration",
            PREFIX + "OpenApiAutoConfiguration",
            PREFIX + "SwaggerUiAutoConfiguration",
            PREFIX + "SpringWebViewAutoConfiguration",
            PREFIX + "SpringWebBatchAutoConfiguration",
            PREFIX + "JspViewAutoConfiguration",
            PREFIX + "SpringWebViewExchangeAutoConfiguration",
            PREFIX + "SpringWebPropertyRefreshAutoConfiguration");

    @Test
    void importsFile_matchesExpectedRegistrationSet() throws Exception {
        List<String> entries = imports();
        assertEquals(EXPECTED.size(), new LinkedHashSet<>(entries).size(), ".imports 存在重复条目");
        assertEquals(EXPECTED, new LinkedHashSet<>(entries), ".imports 与契约集合不一致");
    }

    /** 反向扫描：新增了 {@code @AutoConfiguration} 类却漏登记 .imports 时立即失败。 */
    @Test
    void allAutoConfigurationClasses_areRegistered() throws Exception {
        ClassLoader classLoader = AutoConfigurationContractTest.class.getClassLoader();
        Resource[] resources = new PathMatchingResourcePatternResolver(classLoader)
                .getResources("classpath*:io/springperf/web/autoconfigure/*.class");
        SimpleMetadataReaderFactory factory = new SimpleMetadataReaderFactory(classLoader);
        Set<String> annotated = new TreeSet<>();
        for (Resource resource : resources) {
            String filename = resource.getFilename();
            if (filename == null || filename.contains("$")) {
                continue;
            }
            MetadataReader reader = factory.getMetadataReader(resource);
            if (reader.getAnnotationMetadata().hasAnnotation(AutoConfiguration.class.getName())) {
                annotated.add(reader.getClassMetadata().getClassName());
            }
        }
        assertEquals(EXPECTED, annotated, "存在带 @AutoConfiguration 但未登记到 .imports 的类（或反之）");
    }

    @Test
    void everyRegisteredClass_isAnAutoConfiguration() throws Exception {
        for (String name : imports()) {
            Class<?> type = load(name);
            assertNotNull(type.getDeclaredAnnotation(AutoConfiguration.class), name + " 缺少 @AutoConfiguration");
            assertNull(type.getDeclaredAnnotation(Configuration.class),
                    name + " 仍显式声明 @Configuration（应由 @AutoConfiguration 元注解提供）");
        }
    }

    @Test
    void beanConditionalClasses_declareExplicitOrdering() throws Exception {
        for (String name : imports()) {
            Class<?> type = load(name);
            boolean hasBeanCondition = type.isAnnotationPresent(ConditionalOnBean.class)
                    || Arrays.stream(type.getDeclaredMethods())
                            .anyMatch(m -> m.isAnnotationPresent(ConditionalOnBean.class));
            if (!hasBeanCondition) {
                continue;
            }
            boolean ordered = type.isAnnotationPresent(AutoConfigureAfter.class)
                    || type.isAnnotationPresent(AutoConfigureBefore.class);
            assertTrue(ordered, name + " 使用 @ConditionalOnBean，必须用 @AutoConfigureAfter/Before 固定顺序");
        }
    }

    @Test
    void orderingReferences_areRegisteredAutoConfigurations() throws Exception {
        Set<String> registered = new LinkedHashSet<>(imports());
        for (String name : imports()) {
            Class<?> type = load(name);
            AutoConfigureAfter after = type.getAnnotation(AutoConfigureAfter.class);
            if (after != null) {
                for (Class<?> target : after.value()) {
                    assertTrue(registered.contains(target.getName()),
                            name + " @AutoConfigureAfter 指向未注册类 " + target.getName());
                }
            }
            AutoConfigureBefore before = type.getAnnotation(AutoConfigureBefore.class);
            if (before != null) {
                for (Class<?> target : before.value()) {
                    assertTrue(registered.contains(target.getName()),
                            name + " @AutoConfigureBefore 指向未注册类 " + target.getName());
                }
            }
        }
    }

    private static List<String> imports() throws Exception {
        try (InputStream in = AutoConfigurationContractTest.class.getClassLoader()
                .getResourceAsStream(IMPORTS_RESOURCE)) {
            assertNotNull(in, IMPORTS_RESOURCE + " 不在 classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        }
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, AutoConfigurationContractTest.class.getClassLoader());
    }
}
