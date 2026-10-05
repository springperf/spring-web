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
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.lang.Nullable;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.OptionalValidatorFactoryBean;

import java.util.List;

@ConditionalOnClass(DispatcherHandler.class)
public class SpringWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DispatcherHandler dispatcherHandler() {
        return new DispatcherHandler();
    }

    /**
     * Spring 原生的字符串消息转换器。
     * <p>
     * {@code @RequestBody String} / {@code HttpEntity<String>} 在 Spring 语义下由
     * {@code StringHttpMessageConverter} 处理——它把请求体<b>原样</b>读成字符串（不做 JSON 解析），
     * 且默认支持 {@code text/plain} 与通配类型。
     * </p>
     * <p>
     * 本框架的 {@code HttpBodyCodecRegistry} 会把容器里的 {@code HttpMessageConverter} bean
     * 包装为内部转换器（见 {@code toHttpBodyConverter}），但此前 starter 从未注册过任何
     * {@code HttpMessageConverter}，于是这条通路一直空置：JSON 之外的 String 请求体
     * （如 {@code text/plain}）无转换器可读，直接 400 "not support contentType"。
     * 补上本 bean 后，{@code @RequestBody String} 恢复 Spring 的既有语义。
     * </p>
     * <p>
     * 顺序：优先于 Jackson（{@code JacksonHttpBodyConverter} 的 order 是
     * {@code LOWEST_PRECEDENCE - 50000}），使 String 类型优先由本转换器处理，
     * 避免 JSON 请求下的字符串被 Jackson 按 JSON 解析。
     * </p>
     */
    @Bean
    @ConditionalOnMissingBean
    public StringHttpMessageConverter stringHttpMessageConverter() {
        return new OrderedStringHttpMessageConverter();
    }

    /**
     * {@link StringHttpMessageConverter} 的排序子类。
     * <p>
     * 为什么需要在类上表达顺序：框架的 {@code WebComponentWrapper} 通过
     * {@code AnnotationAwareOrderComparator.findOrder(bean)} 从「被包装的实例」上取 order
     * （读 {@code @Order} 注解或 {@link Ordered} 接口）——bean 方法上的 {@code @Order}
     * 不会体现在实例上。
     * </p>
     * <p>
     * 为什么必须排在 Jackson 之前：{@code @RequestBody String} / {@code HttpEntity<String>}
     * 在 Spring 语义下始终由 {@code StringHttpMessageConverter} <b>原样</b>读取，
     * 即使 Content-Type 是 {@code application/json}——不能落到 Jackson 去按 JSON 解析
     * （那会把非 JSON 字面量的 body 解析失败，返回 400）。
     * </p>
     */
    static class OrderedStringHttpMessageConverter extends StringHttpMessageConverter implements Ordered {

        @Override
        public int getOrder() {
            // JacksonHttpBodyConverter 是 LOWEST_PRECEDENCE - 50000，这里取更小值以优先
            return Ordered.LOWEST_PRECEDENCE - 60000;
        }

        /**
         * 只认领 {@code text/plain} 与通配类型，不抢占声明了<b>具体</b>媒体类型的写入。
         * <p>
         * 父类声明支持 {@code text/plain} 与通配类型。若按父类语义参与写入，当方法声明
         * {@code produces="application/x-custom"} 时，本转换器（order 最靠前）会被先行选中，
         * 抢掉本应由用户为该媒体类型注册的专用 {@code HttpMessageConverter}——
         * 表现为响应体没经过用户转换器（如缺少其前缀）。
         * </p>
         * <p>
         * 写入方本就有明确的 {@code produces}/Accept 协商结果——具体类型应交给能精确处理它的
         * 转换器；本转换器只在协商结果是 {@code text/plain} 或通配（没有更具体的候选）时兜底。
         * 读取侧不受影响（见 {@link #getOrder()}：读路径仍需优先于 Jackson，
         * 以免 {@code @RequestBody String} 被按 JSON 解析）。
         * </p>
         */
        @Override
        public boolean canWrite(@Nullable Class<?> clazz, @Nullable MediaType mediaType) {
            if (!super.canWrite(clazz, mediaType)) {
                return false;
            }
            if (mediaType == null || mediaType.isWildcardType() || mediaType.isWildcardSubtype()) {
                return true;
            }
            return MediaType.TEXT_PLAIN.isCompatibleWith(mediaType);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public ApplicationProperties applicationProperties(Environment environment) {
        return new ApplicationProperties(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public WebContext webContext(List<DispatcherHandler> dispatcherHandlers, ApplicationProperties props) {
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
        public WebMetrics micrometerWebMetrics(io.micrometer.core.instrument.MeterRegistry meterRegistry,
                NettyHttpServer nettyHttpServer) {
            // Register Netty-level Gauges
            io.micrometer.core.instrument.Gauge
                    .builder("netty.connections.active", nettyHttpServer, NettyHttpServer::getActiveConnectionCount)
                    .description("Active TCP connections on the main server").register(meterRegistry);

            io.micrometer.core.instrument.Gauge.builder("netty.eventloop.pending.tasks", nettyHttpServer, server -> {
                EventLoopGroup group = server.getWorkerGroup();
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
     */
    @Bean
    public static BeanFactoryPostProcessor springMvcConflictGuard() {
        return beanFactory -> assertNoSpringMvcConflict();
    }

    private static void assertNoSpringMvcConflict() {
        try {
            Class.forName("org.springframework.web.servlet.DispatcherServlet");
        } catch (ClassNotFoundException e) {
            return;
        }
        throw new IllegalStateException("Detected spring-boot-starter-web on the classpath. "
                + "Perf Actuator integration conflicts with Spring MVC Actuator integration.");
    }
}
