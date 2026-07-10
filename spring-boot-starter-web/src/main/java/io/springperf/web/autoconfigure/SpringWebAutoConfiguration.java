package io.springperf.web.autoconfigure;

import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.SingleThreadEventExecutor;
import io.springperf.web.autoconfigure.actuator.server.SslContextFactory;
import io.springperf.web.autoconfigure.metrics.MicrometerWebMetrics;
import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.core.filter.AccessLogWebFilter;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.server.NettyHttpServer;
import io.springperf.web.server.NettyMetricsHandler;
import io.springperf.web.server.PipelineCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
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

    @Bean
    public WebContext webContext(List<DispatcherHandler> dispatcherHandlers, ApplicationProperties props) {
        assertNoSpringMvcConflict();
        return new WebContext(dispatcherHandlers.get(0), props);
    }

    @Bean
    public NettyHttpServer nettyHttpServer(WebContext webContext, Environment environment,
                                           ObjectProvider<PipelineCustomizer> pipelineCustomizerProvider) {
        boolean http2Enabled = environment.getProperty("server.http2.enabled", boolean.class, false);
        SslContext sslContext = SslContextFactory.createServerSslContext(environment, "server.ssl.", http2Enabled);
        return new NettyHttpServer(webContext, sslContext, pipelineCustomizerProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnProperty(name = "server.accesslog.enabled", havingValue = "true")
    public AccessLogWebFilter accessLogWebFilter() {
        return new AccessLogWebFilter();
    }

    /**
     * 在 Netty 服务器启动完成后发射 {@link WebServerInitializedEvent}，
     * 使 Spring Cloud 服务注册（Nacos/Eureka/Consul）等组件正确感知服务器就绪。
     */
    @Bean
    public ApplicationListener<ApplicationReadyEvent> webServerInitializedEventPublisher(
            NettyHttpServer nettyHttpServer, ApplicationContext applicationContext) {
        return event -> {
            if (nettyHttpServer.isRunning()) {
                WebServer webServer = new PerfWebServer(nettyHttpServer.getActualPort(), nettyHttpServer);
                applicationContext.publishEvent(new PerfWebServerInitializedEvent(webServer, applicationContext));
            }
        };
    }

    @Bean @ConditionalOnMissingBean @ConditionalOnClass(name = "javax.validation.Validator")
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

    private static void assertNoSpringMvcConflict() {
        try { Class.forName("org.springframework.web.servlet.DispatcherServlet"); }
        catch (ClassNotFoundException e) { return; }
        throw new IllegalStateException(
                "Detected spring-boot-starter-web on the classpath. " +
                        "Perf Actuator integration conflicts with Spring MVC Actuator integration.");
    }
}