package io.springperf.web.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import io.springperf.web.context.WebComponent;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.JdkProxyHint;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.TypeHint;
import org.springframework.aot.hint.TypeReference;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.util.concurrent.ListenableFutureCallback;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 双轨可达性元数据对照台账（可执行版）。
 * <p>
 * 全仓只有 starter 带静态 {@code META-INF/native-image/.../*.json}；核心模块没有。
 * 其中约 70 条 {@code io.springperf.web.core.*} registry/resolver/provider 是 AOT 无法自动发现的反射基线，
 * 而 {@code ControllerBeanFactoryInitializationAotProcessor} 只按 BeanFactory 补用户 Controller/DTO。
 * 因此两轨是<b>互补</b>关系，不是「以 RuntimeHints 为唯一权威」：
 * </p>
 *
 * <table border="1">
 * <caption>静态 json ↔ Spring AOT 动态 hints</caption>
 * <tr><th>类别</th><th>仅静态 json</th><th>仅 AOT 动态</th><th>两轨重叠（有意保留兜底）</th></tr>
 * <tr><td>反射</td><td>框架内部类、SPI provider</td>
 *     <td>{@code PerfWebServer*} / {@code ListenableFuture*} / 用户 Controller+DTO</td><td>—</td></tr>
 * <tr><td>代理</td><td>10 个框架 SPI 接口</td><td>{@code ListenableFutureCallback}</td>
 *     <td>{@code WebServerApplicationContext + ConfigurableApplicationContext}</td></tr>
 * <tr><td>资源</td><td>{@code spring.factories}、{@code .imports}、配置文件</td>
 *     <td>按实际文件枚举的模板/静态资源</td><td>配置元数据、四个资源目录</td></tr>
 * </table>
 * <p>
 * 重叠项（事件代理、资源目录）保留为双保险：Spring Boot native 构建始终经 {@code process-aot}，
 * 静态配置与 AOT 生成配置同时生效，不删以免改变既有行为。静态 reflect 中的 4 个
 * {@code *AutoConfiguration} 条目已删除——批次 B 把 15 个类统一为 {@code @AutoConfiguration} 后，
 * AOT 会为 bean 定义生成构造代码，手工留 4/15 既不全又制造「已覆盖」的错觉。
 * </p>
 */
@SuppressWarnings({ "deprecation", "removal" })
class NativeImageHintsConsistencyTest {

    private static final String BASE_PATH = "META-INF/native-image/io.github.springperf/spring-boot-starter-web";

    private static final String IMPORTS_PATH =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private final ObjectMapper mapper = new ObjectMapper();

    /** 动态侧独占：事件路径与异步回调，静态 json 不应重复登记。 */
    @Test
    void dynamicOnlyHints_areNotDuplicatedInStaticJson() throws Exception {
        RuntimeHints hints = registerHints();
        List<String> staticProxies = proxyConfig().stream().flatMap(List::stream).toList();
        List<String> staticReflect = reflectNames();

        assertTrue(hasProxy(hints, ListenableFutureCallback.class));
        assertFalse(staticProxies.contains(ListenableFutureCallback.class.getName()),
                "ListenableFutureCallback 代理应由 RuntimeHints 独占");

        for (Class<?> type : List.of(PerfWebServer.class, PerfWebServerInitializedEvent.class,
                ListenableFutureCallback.class)) {
            assertTrue(hasReflection(hints, type), "RuntimeHints 缺少反射提示: " + type.getName());
            assertFalse(staticReflect.contains(type.getName()),
                    type.getName() + " 应由 RuntimeHints 独占，静态 reflect-config 不应重复登记");
        }
    }

    /** 有意重叠项：双接口事件代理在两轨都存在，作为不改变既有 native 行为的双保险。 */
    @Test
    void overlappingEventProxy_isPresentOnBothTracks() throws Exception {
        RuntimeHints hints = registerHints();
        assertTrue(hasProxy(hints, WebServerApplicationContext.class));
        assertTrue(hasProxy(hints, ConfigurableApplicationContext.class));

        boolean staticOverlap = proxyConfig().stream()
                .anyMatch(entry -> entry.contains(WebServerApplicationContext.class.getName())
                        && entry.contains(ConfigurableApplicationContext.class.getName()));
        assertTrue(staticOverlap, "proxy-config.json 缺少双接口事件代理兜底条目");
    }

    /** 静态侧独占：框架 SPI 接口由静态 json 提供，RuntimeHints 不重复注册。 */
    @Test
    void staticOnlyFrameworkSpiInterfaces_areNotRegisteredByRuntimeHints() throws Exception {
        List<String> staticProxies = proxyConfig().stream().flatMap(List::stream).toList();
        assertTrue(staticProxies.contains(WebComponent.class.getName()),
                "proxy-config.json 缺少框架 SPI 接口基线");
        assertFalse(hasProxy(registerHints(), WebComponent.class),
                "WebComponent 代理应由静态 json 独占，避免两轨重复维护");
    }

    /** 静态侧独占：AOT 无法自动发现框架内部 registry/resolver，必须由静态 reflect 覆盖。 */
    @Test
    void staticOnlyFrameworkInternals_remainInReflectConfig() throws Exception {
        List<String> staticReflect = reflectNames();
        RuntimeHints hints = registerHints();
        for (String name : List.of("io.springperf.web.core.DispatcherHandler",
                "io.springperf.web.core.mapping.MappingRegistry",
                "io.springperf.web.core.arg.ArgumentResolverRegistry",
                "io.springperf.web.core.retval.ReturnValueResolverRegistry")) {
            assertTrue(staticReflect.contains(name), "静态 reflect-config 缺少框架内部类: " + name);
            assertFalse(hasReflection(hints, name), name + " 不应由 RuntimeHints 重复注册");
        }
    }

    /** 防回潮：自动配置类的构造提示由 Spring AOT 生成，静态 reflect 不再手工登记。 */
    @Test
    void staticReflectConfig_doesNotRegisterAutoConfigurationClasses() throws Exception {
        List<String> staticReflect = reflectNames();
        List<String> registered = imports();
        List<String> duplicates = staticReflect.stream().filter(registered::contains).toList();
        assertTrue(duplicates.isEmpty(),
                "自动配置类的构造提示由 process-aot 生成，静态 reflect-config.json 不应重复登记: " + duplicates);
    }

    private RuntimeHints registerHints() {
        RuntimeHints hints = new RuntimeHints();
        new SpringWebRuntimeHints().registerHints(hints, getClass().getClassLoader());
        return hints;
    }

    private static boolean hasProxy(RuntimeHints hints, Class<?> iface) {
        return hints.proxies().jdkProxyHints().map(JdkProxyHint::getProxiedInterfaces)
                .anyMatch(ifaces -> ifaces.contains(TypeReference.of(iface)));
    }

    private static boolean hasReflection(RuntimeHints hints, Class<?> type) {
        return hasReflection(hints, type.getName());
    }

    private static boolean hasReflection(RuntimeHints hints, String typeName) {
        return hints.reflection().typeHints().map(TypeHint::getType)
                .anyMatch(t -> t.getName().equals(typeName));
    }

    private List<List<String>> proxyConfig() throws Exception {
        try (InputStream is = config("proxy-config.json")) {
            CollectionType interfaces = mapper.getTypeFactory().constructCollectionType(List.class, String.class);
            CollectionType type = mapper.getTypeFactory().constructCollectionType(List.class, interfaces);
            return mapper.readValue(is, type);
        }
    }

    private List<String> reflectNames() throws Exception {
        try (InputStream is = config("reflect-config.json")) {
            CollectionType type = mapper.getTypeFactory().constructCollectionType(List.class,
                    mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            List<Map<String, Object>> entries = mapper.readValue(is, type);
            return entries.stream().map(e -> (String) e.get("name")).toList();
        }
    }

    private InputStream config(String fileName) {
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
}
