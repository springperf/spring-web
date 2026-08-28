package io.springperf.web.autoconfigure;

import io.springperf.web.view.ViewProperties;
import io.springperf.web.view.arg.ModelArgumentResolverProvider;
import io.springperf.web.view.retval.ViewReturnValueResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnClass(name = "io.springperf.web.view.ViewResolverRegistry")
public class SpringWebViewAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ViewReturnValueResolver viewReturnValueResolver() {
        return new ViewReturnValueResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelArgumentResolverProvider modelArgumentResolverProvider() {
        return new ModelArgumentResolverProvider();
    }

    @Configuration
    @ConditionalOnClass(name = "org.thymeleaf.TemplateEngine")
    @Conditional(ViewEngineCondition.Thymeleaf.class)
    public static class ThymeleafConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public io.springperf.web.view.thymeleaf.ThymeleafViewResolver thymeleafViewResolver() {
            log.info("Thymeleaf view resolver auto-configured");
            return new io.springperf.web.view.thymeleaf.ThymeleafViewResolver();
        }
    }

    @Configuration
    @ConditionalOnClass(name = "freemarker.template.Configuration")
    @Conditional(ViewEngineCondition.FreeMarker.class)
    public static class FreeMarkerConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public io.springperf.web.view.freemarker.FreemarkerViewResolver freemarkerViewResolver() {
            log.info("FreeMarker view resolver auto-configured");
            return new io.springperf.web.view.freemarker.FreemarkerViewResolver();
        }
    }

    @Configuration
    @ConditionalOnClass(name = "org.beetl.core.GroupTemplate")
    @Conditional(ViewEngineCondition.Beetl.class)
    public static class BeetlConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public io.springperf.web.view.beetl.BeetlViewResolver beetlViewResolver() {
            log.info("Beetl view resolver auto-configured");
            return new io.springperf.web.view.beetl.BeetlViewResolver();
        }
    }
}