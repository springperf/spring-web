package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import io.springperf.web.server.NettyHttpServer;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Netty 服务器启动完成后发射 {@code WebServerInitializedEvent}， 使 Spring Cloud
 * 服务注册（Nacos/Eureka/Consul）等组件正确感知服务器就绪。
 * <p>
 * 同时承载 GraalVM native-image 可达性提示 {@link SpringWebRuntimeHints}：事件路径的 JDK 代理 + 反射由 Spring AOT
 * 构建期采集。
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(SpringWebRuntimeHints.class)
public class WebServerInitializedEventAutoConfiguration {

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
}
