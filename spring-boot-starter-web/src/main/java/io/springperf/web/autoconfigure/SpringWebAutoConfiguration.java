package io.springperf.web.autoconfigure;

import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.springperf.web.autoconfigure.actuator.server.SslContextFactory;
import io.springperf.web.autoconfigure.metrics.MicrometerWebMetrics;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.filter.AccessLogWebFilter;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.server.NettyHttpServer;
import io.springperf.web.server.NettyMetricsHandler;
import io.springperf.web.server.PipelineCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean;

import java.util.List;

@ConditionalOnClass(DispatcherHandler.class)
public class SpringWebAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    public DispatcherHandler dispatcherHandler() { return new DispatcherHandler(); }

    @Bean @ConditionalOnMissingBean
    public ApplicationProperties applicationProperties() { return new ApplicationProperties(); }

    @Bean @ConditionalOnMissingBean
    public WebContext webContext(List<DispatcherHandler> dispatcherHandlers, ApplicationProperties props) {
        return new WebContext(dispatcherHandlers.get(0), props);
    }

    @Bean @ConditionalOnMissingBean
    public NettyHttpServer nettyHttpServer(WebContext webContext, Environment environment,
                                           ObjectProvider<PipelineCustomizer> pipelineCustomizerProvider) {
        boolean http2Enabled = environment.getProperty("server.http2.enabled", boolean.class, false);
        SslContext sslContext = SslContextFactory.createServerSslContext(environment, "server.ssl.", http2Enabled);
        NettyHttpServer server = new NettyHttpServer(webContext, sslContext, pipelineCustomizerProvider.getIfAvailable());
        webContext.registerWebComponent(server);
        return server;
    }

    @Bean
    @ConditionalOnProperty(name = "server.accesslog.enabled", havingValue = "true")
    public AccessLogWebFilter accessLogWebFilter(Environment environment) {
        String format = environment.getProperty("server.accesslog.format");
        return new AccessLogWebFilter(format);
    }

    @Bean @ConditionalOnMissingBean @ConditionalOnClass(name = "jakarta.validation.Validator")
    public Validator validator() { return new OptionalValidatorFactoryBean(); }

    @Configuration
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerWebMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public WebMetrics micrometerWebMetrics(io.micrometer.core.instrument.MeterRegistry meterRegistry,
                                                NettyHttpServer nettyHttpServer) {
            // Register Netty-level Gauges
            io.micrometer.core.instrument.Gauge.builder("netty.connections.active",
                            NettyMetricsHandler.INSTANCE, NettyMetricsHandler::getActiveConnectionCount)
                    .description("Active TCP connections")
                    .register(meterRegistry);

            io.micrometer.core.instrument.Gauge.builder("netty.eventloop.pending.tasks",
                            nettyHttpServer, server -> {
                                EventLoopGroup group = server.getWorkerGroup();
                                long total = 0;
                                for (EventExecutor executor : group) {
                                    if (executor instanceof SingleThreadEventExecutor) {
                                        total += ((SingleThreadEventExecutor) executor).pendingTasks();
                                    }
                                }
                                return (double) total;
                            })
                    .description("Pending tasks across all EventLoops")
                    .register(meterRegistry);

            return new MicrometerWebMetrics(meterRegistry);
        }
    }

    /**
     * D9：冲突检测提前到容器初始化早期。BeanFactoryPostProcessor 在 bean 定义加载后、
     * 实例化前执行，比原 webContext bean 方法（实例化阶段）更早暴露问题。
     * 静态 @Bean 确保本类实例化前即可注册该 post-processor。
     */
    @Bean
    public static BeanFactoryPostProcessor springMvcConflictGuard() {
        return beanFactory -> assertNoSpringMvcConflict();
    }

    private static void assertNoSpringMvcConflict() {
        try { Class.forName("org.springframework.web.servlet.DispatcherServlet"); }
        catch (ClassNotFoundException e) { return; }
        throw new IllegalStateException(
                "Detected spring-boot-starter-web on the classpath. " +
                        "Perf Actuator integration conflicts with Spring MVC Actuator integration.");
    }
}