package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.Boot4WebServerInitializedEventBridge;
import io.springperf.web.server.NettyHttpServer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4 专属：Netty 服务器启动完成后发射 {@code WebServerInitializedEvent}
 * （SB4 新包 {@code org.springframework.boot.web.server.context}），
 * 使 Spring Cloud 服务注册（Nacos/Eureka/Consul）等组件正确感知服务器就绪。
 *
 * <p>SB4 移除了 SB3 的 {@code org.springframework.boot.web.context.WebServerInitializedEvent}
 * （SB3 事件适配见 {@link WebServerInitializedEventAutoConfiguration}），且 SB4 事件为抽象类。
 * 本配置类用字符串形式 {@code @ConditionalOnClass} 守卫新包名、仅在 SB4 激活，SB3 下条件不满足、
 * 类不加载。桥接实现见 {@link Boot4WebServerInitializedEventBridge}，编译期零引用 SB4 类。
 * 桥接失败仅告警降级，不影响应用启动。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.boot.web.server.context.WebServerInitializedEvent")
public class Boot4WebServerInitializedEventAutoConfiguration {

    private static final Log log = LogFactory.getLog(Boot4WebServerInitializedEventAutoConfiguration.class);

    @Bean
    public ApplicationListener<ApplicationReadyEvent> boot4WebServerInitializedEventPublisher(
            NettyHttpServer nettyHttpServer, ApplicationContext applicationContext) {
        return event -> {
            if (nettyHttpServer.isRunning()) {
                try {
                    Boot4WebServerInitializedEventBridge.publish(nettyHttpServer, applicationContext);
                } catch (Throwable t) {
                    log.warn("Failed to publish SB4 WebServerInitializedEvent; " +
                            "service registration may miss the server port", t);
                }
            }
        };
    }
}
