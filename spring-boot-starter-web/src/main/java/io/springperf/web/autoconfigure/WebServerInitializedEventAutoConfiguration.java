package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import io.springperf.web.server.NettyHttpServer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Netty 服务器启动完成后发射 {@code WebServerInitializedEvent}， 使 Spring Cloud 服务注册（Nacos/Eureka/Consul）等组件正确感知服务器就绪。
 * <p>
 * 本分支（4.1.x）专用 Spring Boot 4，直接使用 SB4 的事件类
 * （{@code org.springframework.boot.web.server.context.WebServerInitializedEvent}）。
 * </p>
 * <p>
 * <b>与早期实现的区别</b>：此前为在同一份代码里兼顾 SB3/SB4，本类用字符串形式的
 * {@code @ConditionalOnClass} 与 SB3 版本互斥，SB4 路径还要经运行时 ASM 桥接
 * （{@code Boot4WebServerInitializedEventBridge}）生成事件子类。专用化后两者都已移除：
 * 事件类可编译期直接引用，故改为直接构造 {@link PerfWebServerInitializedEvent}。
 * </p>
 * <p>
 * {@link ImportRuntimeHints} 随本类挂载 {@link SpringWebRuntimeHints}：本类是事件路径的
 * 唯一入口，那些 GraalVM 提示（代理接口、事件类型反射、资源）正对应这条路径。
 * </p>
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(SpringWebRuntimeHints.class)
public class WebServerInitializedEventAutoConfiguration {

    @Bean
    public ApplicationListener<ApplicationReadyEvent> webServerInitializedEventPublisher(
            NettyHttpServer nettyHttpServer, ApplicationContext applicationContext) {
        return event -> {
            if (nettyHttpServer.isRunning()) {
                PerfWebServer webServer = new PerfWebServer(nettyHttpServer.getActualPort(), nettyHttpServer);
                applicationContext.publishEvent(new PerfWebServerInitializedEvent(webServer, applicationContext));
            }
        };
    }
}
