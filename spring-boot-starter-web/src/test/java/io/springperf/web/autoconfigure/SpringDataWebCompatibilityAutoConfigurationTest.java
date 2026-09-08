package io.springperf.web.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验证 {@link SpringDataWebCompatibilityAutoConfiguration.ProjectingArgumentResolverCleanupProcessor}：
 * classpath 无 RequestMappingHandlerAdapter 时移除 Spring Data 的 BPP bean，防止 NoClassDefFoundError。
 */
class SpringDataWebCompatibilityAutoConfigurationTest {

    private static final String BPP_BEAN_NAME = "projectingArgumentResolverBeanPostProcessor";

    private final SpringDataWebCompatibilityAutoConfiguration.ProjectingArgumentResolverCleanupProcessor processor =
            new SpringDataWebCompatibilityAutoConfiguration.ProjectingArgumentResolverCleanupProcessor();

    @Test
    void postProcessBeanDefinitionRegistry_containsBpp_removes() {
        BeanDefinitionRegistry registry = mock(BeanDefinitionRegistry.class);
        when(registry.containsBeanDefinition(BPP_BEAN_NAME)).thenReturn(true);

        processor.postProcessBeanDefinitionRegistry(registry);

        verify(registry).removeBeanDefinition(BPP_BEAN_NAME);
    }

    @Test
    void postProcessBeanDefinitionRegistry_noBpp_noOp() {
        BeanDefinitionRegistry registry = mock(BeanDefinitionRegistry.class);
        when(registry.containsBeanDefinition(BPP_BEAN_NAME)).thenReturn(false);

        processor.postProcessBeanDefinitionRegistry(registry);

        verify(registry, never()).removeBeanDefinition(BPP_BEAN_NAME);
    }

    @Test
    void postProcessBeanFactory_noOp() {
        assertDoesNotThrow(() -> processor.postProcessBeanFactory(mock(ConfigurableListableBeanFactory.class)));
    }

    @Test
    void projectingArgumentResolverCleanupProcessor_returnsPostProcessor() {
        BeanDefinitionRegistryPostProcessor processor =
                SpringDataWebCompatibilityAutoConfiguration.projectingArgumentResolverCleanupProcessor();
        assertNotNull(processor);
    }
}