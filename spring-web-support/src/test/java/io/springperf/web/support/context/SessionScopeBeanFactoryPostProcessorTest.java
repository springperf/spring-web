package io.springperf.web.support.context;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;

import static org.junit.jupiter.api.Assertions.*;

class SessionScopeBeanFactoryPostProcessorTest {

    @Test
    void registersSessionScope() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        SessionScopeBeanFactoryPostProcessor processor = new SessionScopeBeanFactoryPostProcessor();
        assertDoesNotThrow(() -> processor.postProcessBeanFactory(bf));
        assertTrue(bf.getRegisteredScope(SessionScopeBeanFactoryPostProcessor.SESSION_SCOPE_NAME) != null,
                "session scope 应被注册");
    }

    @Test
    void sessionScopeUsed_withoutSpringAop_throws() {
        // 模拟 spring-aop 缺失：ClassLoader 无法加载 ScopedProxyFactoryBean
        ClassLoader noAopLoader = new ClassLoader(null) {
            @Override
            public Class<?> loadClass(String name) throws ClassNotFoundException {
                if (name.equals("org.springframework.aop.scope.ScopedProxyFactoryBean")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name);
            }
        };
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.setBeanClassLoader(noAopLoader);
        BeanDefinition bd = new RootBeanDefinition(String.class);
        bd.setScope("session");
        bf.registerBeanDefinition("sessionBean", bd);

        SessionScopeBeanFactoryPostProcessor processor = new SessionScopeBeanFactoryPostProcessor();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> processor.postProcessBeanFactory(bf));
        assertTrue(ex.getMessage().contains("spring-aop"), "提示应包含 spring-aop 缺失信息");
    }

    @Test
    void noSessionScopeUsed_noThrow() {
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        BeanDefinition bd = new RootBeanDefinition(String.class);
        bd.setScope("singleton");
        bf.registerBeanDefinition("normalBean", bd);

        SessionScopeBeanFactoryPostProcessor processor = new SessionScopeBeanFactoryPostProcessor();
        assertDoesNotThrow(() -> processor.postProcessBeanFactory(bf));
    }

    @Test
    void order_isLowestPrecedence() {
        SessionScopeBeanFactoryPostProcessor processor = new SessionScopeBeanFactoryPostProcessor();
        assertEquals(org.springframework.core.Ordered.LOWEST_PRECEDENCE, processor.getOrder());
    }
}
