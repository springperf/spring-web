package io.springperf.web.support.context;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.util.ClassUtils;
import org.springframework.web.context.request.SessionScope;

/**
 * 注册 {@code session} 作用域，使 {@code @SessionScope} bean 可用。
 * <p>实现：{@link SessionScope}（Spring 原生，基于 {@code RequestContextHolder}
 * 的 {@code ServletRequestAttributes}，随 {@code SupportDispatcherHandler} 初始化）。
 * <p>fail-fast：{@code @SessionScope} 默认 {@code proxyMode=TARGET_CLASS} 依赖
 * spring-aop 的 {@code ScopedProxyFactoryBean} 生成 scoped-proxy。若 classpath 缺失
 * spring-aop 且存在 session 作用域 bean，启动即抛异常而非运行期 NoClassDefFoundError。
 * <p>注意：spring-aop 在本模块为 {@code provided} 依赖（小众需求不强制传递）。
 */
public class SessionScopeBeanFactoryPostProcessor implements BeanFactoryPostProcessor, PriorityOrdered {

    public static final String SESSION_SCOPE_NAME = "session";

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        boolean sessionScopeUsed = isSessionScopeUsed(beanFactory);
        if (sessionScopeUsed && !isSpringAopPresent(beanFactory)) {
            throw new IllegalStateException(
                    "@SessionScope requires spring-aop on the classpath (ScopedProxyFactoryBean for TARGET_CLASS proxy). "
                            + "Add org.springframework:spring-aop as a runtime dependency, or remove @SessionScope usage.");
        }
        // SessionScope 本身位于 spring-web（无 spring-aop 依赖），无论是否使用都注册，避免 getBean 时 scope 缺失
        beanFactory.registerScope(SESSION_SCOPE_NAME, new SessionScope());
    }

    private static boolean isSessionScopeUsed(ConfigurableListableBeanFactory beanFactory) {
        for (String name : beanFactory.getBeanDefinitionNames()) {
            BeanDefinition bd = beanFactory.getBeanDefinition(name);
            if (SESSION_SCOPE_NAME.equals(bd.getScope())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSpringAopPresent(ConfigurableListableBeanFactory beanFactory) {
        return ClassUtils.isPresent("org.springframework.aop.scope.ScopedProxyFactoryBean",
                beanFactory.getBeanClassLoader());
    }

    @Override
    public int getOrder() {
        // 需在 ConfigurationClassPostProcessor 解析完 @Scope/@SessionScope 之后执行，
        // 才能正确检测 bean 作用域；注册 scope 本身也须先于任何 session bean 实例化。
        return Ordered.LOWEST_PRECEDENCE;
    }
}
