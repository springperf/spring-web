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
import io.springperf.web.core.filter.AccessLogWriter;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.server.NettyHttpServer;
import io.springperf.web.server.PipelineCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean;

import java.util.List;

@AutoConfiguration
@ConditionalOnClass(DispatcherHandler.class)
public class SpringWebAutoConfiguration {

    /** 显式关闭 Spring MVC 冲突守卫；用于官方 spring-webmvc 只是三方库传递依赖的场景。 */
    static final String MVC_CONFLICT_CHECK_PROPERTY = "springperf.web.mvc-conflict-check.enabled";

    /** 官方 Spring MVC 的标志类；本框架重写的 org.springframework.web.servlet.* 不含此类。 */
    static final String SPRING_MVC_INDICATOR = "org.springframework.web.servlet.DispatcherServlet";

    @Bean
    @ConditionalOnMissingBean
    public DispatcherHandler dispatcherHandler() {
        return new DispatcherHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    public ApplicationProperties applicationProperties(Environment environment) {
        return new ApplicationProperties(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public WebContext webContext(List<DispatcherHandler> dispatcherHandlers, ApplicationProperties props) {
        if (dispatcherHandlers.isEmpty()) {
            throw new IllegalStateException("No DispatcherHandler bean is available in the application context; "
                    + "ensure SpringWebAutoConfiguration is not excluded and a DispatcherHandler bean is declared.");
        }
        // Spring 注入的 List 已按 @Order/Ordered 排序：servlet 桥接层可同时注册 SupportDispatcherHandler，
        // 此处取优先级最高的一个，与既有行为保持一致。
        return new WebContext(dispatcherHandlers.get(0), props);
    }

    @Bean
    @ConditionalOnMissingBean
    public NettyHttpServer nettyHttpServer(WebContext webContext, Environment environment,
            ObjectProvider<PipelineCustomizer> pipelineCustomizerProvider) {
        boolean http2Enabled = environment.getProperty("server.http2.enabled", boolean.class, false);
        SslContext sslContext = SslContextFactory.createServerSslContext(environment, "server.ssl.", http2Enabled);
        NettyHttpServer server = new NettyHttpServer(webContext, sslContext,
                pipelineCustomizerProvider.getIfAvailable());
        webContext.registerWebComponent(server);
        return server;
    }

    @Bean
    @ConditionalOnProperty(name = "server.accesslog.enabled", havingValue = "true")
    public AccessLogWebFilter accessLogWebFilter(Environment environment, ApplicationProperties props) {
        String format = environment.getProperty("server.accesslog.format");
        return new AccessLogWebFilter(format, AccessLogWriter.fromProperties(props));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "jakarta.validation.Validator")
    public Validator validator() {
        return new OptionalValidatorFactoryBean();
    }

    @Configuration
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MicrometerWebMetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        // 必须与类级 @ConditionalOnClass 并存：@ConditionalOnClass 只证明 classpath 上有这个类，
        // 不证明容器里有 MeterRegistry Bean。而本方法的 meterRegistry 是强依赖参数——
        // 用户引入 micrometer-tracing-bridge-* 等只带 micrometer-core、不创建 registry 的库时
        // （spring-boot-actuator-autoconfigure 在本模块是 provided），缺少本守卫会直接抛
        // NoSuchBeanDefinitionException 使应用起不来。
        // 与 SpringWebBatchAutoConfiguration 的写法保持一致。
        @ConditionalOnBean(io.micrometer.core.instrument.MeterRegistry.class)
        public WebMetrics micrometerWebMetrics(io.micrometer.core.instrument.MeterRegistry meterRegistry,
                NettyHttpServer nettyHttpServer) {
            // Register Netty-level Gauges
            io.micrometer.core.instrument.Gauge
                    .builder("netty.connections.active", nettyHttpServer, NettyHttpServer::getActiveConnectionCount)
                    .description("Active TCP connections on the main server").register(meterRegistry);

            io.micrometer.core.instrument.Gauge.builder("netty.eventloop.pending.tasks", nettyHttpServer, server -> {
                EventLoopGroup group = server.getWorkerGroup();
                if (group == null) {
                    // NettyHttpServer.start() 之前 workerGroup 尚未创建，Gauge 采集不能因此抛 NPE。
                    return 0d;
                }
                long total = 0;
                for (EventExecutor executor : group) {
                    if (executor instanceof SingleThreadEventExecutor) {
                        total += ((SingleThreadEventExecutor) executor).pendingTasks();
                    }
                }
                return (double) total;
            }).description("Pending tasks across all EventLoops").register(meterRegistry);

            return new MicrometerWebMetrics(meterRegistry);
        }
    }

    /**
     * D9：冲突检测提前到容器初始化早期。BeanFactoryPostProcessor 在 bean 定义加载后、 实例化前执行，比原 webContext bean 方法（实例化阶段）更早暴露问题。 静态 @Bean
     * 确保本类实例化前即可注册该 post-processor。
     * <p>
     * 判定只依赖官方 Spring MVC 的 {@code DispatcherServlet}；框架自行重写的
     * {@code org.springframework.web.servlet.*} 不含该类，因此不会误报。若该类只是三方库传递依赖、
     * 并不会启用 Spring MVC，可用 {@link #MVC_CONFLICT_CHECK_PROPERTY}=false 显式关闭本守卫。
     * </p>
     */
    @Bean
    @ConditionalOnProperty(name = MVC_CONFLICT_CHECK_PROPERTY, havingValue = "true", matchIfMissing = true)
    public static BeanFactoryPostProcessor springMvcConflictGuard() {
        ClassLoader classLoader = SpringWebAutoConfiguration.class.getClassLoader();
        return beanFactory -> assertNoSpringMvcConflict(ClassUtils.isPresent(SPRING_MVC_INDICATOR, classLoader));
    }

    /** 显式传入判定结果，便于覆盖「冲突存在」这一在测试 classpath 上无法真实复现的分支。 */
    static void assertNoSpringMvcConflict(boolean springMvcPresent) {
        if (!springMvcPresent) {
            return;
        }
        throw new IllegalStateException("Detected Spring MVC (" + SPRING_MVC_INDICATOR
                + ") on the classpath together with perf-spring-web: both provide a dispatcher for HTTP requests. "
                + "Remove spring-webmvc / spring-boot-starter-web from the dependencies, or set "
                + MVC_CONFLICT_CHECK_PROPERTY + "=false to disable this guard when Spring MVC is not actually used.");
    }
}
