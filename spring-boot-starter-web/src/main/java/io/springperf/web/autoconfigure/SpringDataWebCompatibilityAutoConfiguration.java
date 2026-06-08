package io.springperf.web.autoconfigure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configuration to remove Spring Data's {@code ProjectingArgumentResolverRegistrar}
 * BeanPostProcessor when spring-webmvc is not on the classpath.
 *
 * <p>Spring Data Common 2.7.x auto-detects the presence of {@code WebMvcConfigurer}
 * via {@code @ConditionalOnClass}, which matches the framework's shim interface in
 * spring-web-support. This triggers {@code @EnableSpringDataWebSupport} which imports
 * {@code ProjectingArgumentResolverRegistrar}, whose inner {@code BeanPostProcessor}
 * references {@code RequestMappingHandlerAdapter.class} in its bytecode.
 *
 * <p>Since this framework replaces Spring MVC, {@code RequestMappingHandlerAdapter}
 * is not on the classpath, causing a {@code NoClassDefFoundError} when the JVM
 * resolves the class reference. This configuration removes the BPP bean definition
 * before it can be instantiated, preventing the error entirely.
 *
 * <p>The {@code ProjectingArgumentResolver} for Spring Data projections is still
 * registered through Spring Data's {@code SpringDataWebConfiguration} which implements
 * {@code WebMvcConfigurer#addArgumentResolvers()}, and is bridged by the framework's
 * existing {@code WebMvcConfigurerBridge}.
 *
 * @author huangcanda
 * @since 1.0.1
 */
@Configuration
@ConditionalOnClass(name = "org.springframework.data.web.config.ProjectingArgumentResolverRegistrar")
@ConditionalOnMissingClass("org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter")
public class SpringDataWebCompatibilityAutoConfiguration {

    @Bean
    static BeanDefinitionRegistryPostProcessor projectingArgumentResolverCleanupProcessor() {
        return new ProjectingArgumentResolverCleanupProcessor();
    }

    static class ProjectingArgumentResolverCleanupProcessor implements BeanDefinitionRegistryPostProcessor {

        private static final Logger log = LoggerFactory.getLogger(ProjectingArgumentResolverCleanupProcessor.class);

        private static final String BPP_BEAN_NAME =
                "projectingArgumentResolverBeanPostProcessor";


        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
            if (registry.containsBeanDefinition(BPP_BEAN_NAME)) {
                registry.removeBeanDefinition(BPP_BEAN_NAME);
                log.info("Removed ProjectingArgumentResolverRegistrar's BeanPostProcessor " +
                        "to prevent NoClassDefFoundError for RequestMappingHandlerAdapter. " +
                        "Projection resolvers from SpringDataWebConfiguration are still bridged via WebMvcConfigurer.");
            }
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        }
    }
}
