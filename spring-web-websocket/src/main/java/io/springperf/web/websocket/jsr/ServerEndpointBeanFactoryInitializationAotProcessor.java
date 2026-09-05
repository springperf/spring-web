package io.springperf.web.websocket.jsr;

import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.server.ServerEndpoint;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.ExecutableMode;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * GraalVM native-image AOT 处理器：为 Bean 发现的 {@code @ServerEndpoint} 端点注册
 * 反射可达性提示。
 *
 * <p><b>为什么需要它</b>：native 下 {@link JsrEndpointScanner} 不会做 classpath 扫描
 * （封闭世界不可用），只依赖 {@link JsrEndpointScanner#scanBeans()} 的 Bean 发现。
 * {@link JsrEndpointWebSocketHandler} 对端点做两处反射调用：
 * <ul>
 *   <li>实例化：{@code endpointClass.getDeclaredConstructor().newInstance()}（无参构造器）；</li>
 *   <li>回调：{@code @OnOpen}/{@code @OnMessage}/{@code @OnClose}/{@code @OnError}
 *       方法的 {@code setAccessible + invoke}。</li>
 * </ul>
 * 这些在 native 下都需要 hint，本处理器在 AOT 构建期补齐。
 *
 * <p>与运行时 {@link JsrEndpointScanner#scanBeans()} 同口径：
 * 扫描 {@code getBeanNamesForAnnotation(ServerEndpoint.class)}——Bean 注册的端点
 * （native 下要求用户把 {@code @ServerEndpoint} 端点显式注册为 Spring Bean）。
 *
 * <p>通过本模块 {@code META-INF/spring/aot.factories} 注册，
 * 由 Spring Boot {@code process-aot} 构建期调用；JVM 运行时完全不触发，零影响。
 */
public class ServerEndpointBeanFactoryInitializationAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public BeanFactoryInitializationAotContribution processAheadOfTime(ConfigurableListableBeanFactory beanFactory) {
        Set<Class<?>> endpointClasses = new LinkedHashSet<>();

        String[] endpointNames = beanFactory.getBeanNamesForAnnotation(ServerEndpoint.class);
        for (String beanName : endpointNames) {
            Class<?> beanType = beanFactory.getType(beanName);
            if (beanType == null) {
                continue;
            }
            endpointClasses.add(ClassUtils.getUserClass(beanType));
        }

        if (endpointClasses.isEmpty()) {
            return null;
        }
        return new Contribution(endpointClasses);
    }

    /** AOT 贡献：在构建期把收集到的 hints 注册进 RuntimeHints */
    private static final class Contribution implements BeanFactoryInitializationAotContribution {

        private final Set<Class<?>> endpointClasses;

        Contribution(Set<Class<?>> endpointClasses) {
            this.endpointClasses = endpointClasses;
        }

        @Override
        public void applyTo(GenerationContext generationContext, BeanFactoryInitializationCode beanFactoryInitializationCode) {
            RuntimeHints hints = generationContext.getRuntimeHints();
            for (Class<?> endpointClass : endpointClasses) {
                // 无参构造器实例化（getDeclaredConstructor().newInstance()）
                hints.reflection().registerType(endpointClass,
                        MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);

                // @OnOpen/@OnMessage/@OnClose/@OnError 回调方法（setAccessible + invoke）
                for (Method method : ReflectionUtils.getUniqueDeclaredMethods(endpointClass)) {
                    if (AnnotatedElementUtils.findMergedAnnotation(method, OnOpen.class) != null
                            || AnnotatedElementUtils.findMergedAnnotation(method, OnMessage.class) != null
                            || AnnotatedElementUtils.findMergedAnnotation(method, OnClose.class) != null
                            || AnnotatedElementUtils.findMergedAnnotation(method, OnError.class) != null) {
                        hints.reflection().registerMethod(method, ExecutableMode.INVOKE);
                    }
                }
            }
        }
    }
}