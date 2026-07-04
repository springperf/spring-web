package io.springperf.web.autoconfigure;

import io.springperf.web.batch.BatchRegistry;
import io.springperf.web.context.WebContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnClass(name = "io.springperf.web.batch.annotation.BatchMapping")
public class SpringWebBatchAutoConfiguration {

    @Bean
    @ConditionalOnBean(WebContext.class)
    public BatchRegistry batchRegistry(WebContext webContext) {
        BatchRegistry registry = new BatchRegistry();
        webContext.registerWebComponent(registry);
        return registry;
    }
}
