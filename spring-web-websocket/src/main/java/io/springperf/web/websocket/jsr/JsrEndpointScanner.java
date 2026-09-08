package io.springperf.web.websocket.jsr;

import javax.websocket.server.ServerEndpoint;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 扫描 classpath 中带 {@link ServerEndpoint} 注解的端点类。
 *
 * <p>扫描范围：</p>
 * <ol>
 *   <li>通过 Spring Boot {@link AutoConfigurationPackages} 获取应用基础包，做 classpath 组件扫描；</li>
 *   <li>补充 Spring 容器中注册为 Bean 的 {@code @ServerEndpoint} 类型（用户显式注册的端点）。</li>
 * </ol>
 *
 * <p>GraalVM native-image：封闭世界下 classpath 运行时扫描不可用，
 * 检测到 native 环境时跳过 {@code scanClasspath()}，仅依赖 Spring Bean 发现
 * （{@code scanBeans()}）。因此 native 场景要求用户把 {@code @ServerEndpoint} 端点
 * 显式注册为 Spring Bean。</p>
 *
 * @author huangcanda
 * @since 3.5.6
 */
public class JsrEndpointScanner {

    /** GraalVM native-image 运行时会在系统属性中设置此值，用于检测原生镜像环境。 */
    private static final boolean IN_NATIVE_IMAGE =
            System.getProperty("org.graalvm.nativeimage.imagecode") != null;

    private final ApplicationContext applicationContext;

    public JsrEndpointScanner(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 扫描所有 {@code @ServerEndpoint} 端点类，结果去重。
     */
    public List<Class<?>> scan() {
        Set<Class<?>> result = new LinkedHashSet<>();
        if (!IN_NATIVE_IMAGE) {
            result.addAll(scanClasspath());
        }
        result.addAll(scanBeans());
        return new ArrayList<>(result);
    }

    private List<Class<?>> scanClasspath() {
        List<Class<?>> classes = new ArrayList<>();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ServerEndpoint.class));
        for (String basePackage : resolveBasePackages()) {
            scanner.findCandidateComponents(basePackage).forEach(bd -> {
                Class<?> type = resolveClassName(bd.getBeanClassName());
                if (type != null) {
                    classes.add(type);
                }
            });
        }
        return classes;
    }

    private List<Class<?>> scanBeans() {
        List<Class<?>> classes = new ArrayList<>();
        if (applicationContext instanceof ListableBeanFactory) {
            String[] names = ((ListableBeanFactory) applicationContext)
                    .getBeanNamesForAnnotation(ServerEndpoint.class);
            if (names == null) {
                return classes;
            }
            for (String name : names) {
                Class<?> type = applicationContext.getType(name);
                if (type != null) {
                    classes.add(type);
                }
            }
        }
        return classes;
    }

    private List<String> resolveBasePackages() {
        List<String> packages = new ArrayList<>();
        if (applicationContext != null && applicationContext.getParent() == null) {
            try {
                List<String> autoPackages = AutoConfigurationPackages.get(
                        applicationContext.getAutowireCapableBeanFactory());
                packages.addAll(autoPackages);
            } catch (Exception ex) {
                // 非 Spring Boot 主程序，回退到端点所在包扫描（由调用方处理）
            }
        }
        return packages;
    }

    private Class<?> resolveClassName(String className) {
        try {
            return ClassUtils.forName(className, applicationContext.getClassLoader());
        } catch (ClassNotFoundException | LinkageError ex) {
            return null;
        }
    }
}
