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
 * 鎵弿 classpath 涓甫 {@link ServerEndpoint} 娉ㄨВ鐨勭鐐圭被銆?
 *
 * <p>鎵弿鑼冨洿锛?/p>
 * <ol>
 *   <li>閫氳繃 Spring Boot {@link AutoConfigurationPackages} 鑾峰彇搴旂敤鍩虹鍖咃紝鍋?classpath 缁勪欢鎵弿锛?/li>
 *   <li>琛ュ厖 Spring 瀹瑰櫒涓敞鍐屼负 Bean 鐨?{@code @ServerEndpoint} 绫诲瀷锛堢敤鎴锋樉寮忔敞鍐岀殑绔偣锛夈€?/li>
 * </ol>
 *
 * @author huangcanda
 * @since 3.2.5
 */
public class JsrEndpointScanner {

    private final ApplicationContext applicationContext;

    public JsrEndpointScanner(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 鎵弿鎵€鏈?{@code @ServerEndpoint} 绔偣绫伙紝缁撴灉鍘婚噸銆?
     */
    public List<Class<?>> scan() {
        Set<Class<?>> result = new LinkedHashSet<>();
        result.addAll(scanClasspath());
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
                // 闈?Spring Boot 涓荤▼搴忥紝鍥為€€鍒扮鐐规墍鍦ㄥ寘鎵弿锛堢敱璋冪敤鏂瑰鐞嗭級
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
