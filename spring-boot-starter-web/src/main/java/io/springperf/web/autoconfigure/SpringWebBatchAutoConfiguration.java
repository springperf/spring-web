package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.batch.MicrometerBatchMetrics;
import io.springperf.web.batch.BatchRegistry;
import io.springperf.web.batch.metrics.BatchMetrics;
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

    @Configuration
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerBatchMetricsConfiguration {

        @Bean
        @ConditionalOnBean({WebContext.class, io.micrometer.core.instrument.MeterRegistry.class})
        public BatchMetrics batchMetrics(BatchRegistry batchRegistry,
                                         io.micrometer.core.instrument.MeterRegistry meterRegistry) {
            MicrometerBatchMetrics metrics = new MicrometerBatchMetrics(meterRegistry);
            batchRegistry.setMetrics(metrics);
            return metrics;
        }
    }
}