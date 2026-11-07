package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import io.springperf.web.server.NettyHttpServer;
import io.springperf.web.server.NettyServerStartedEvent;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

/**
 * 把核心模块的 {@link NettyServerStartedEvent} 转换成 Spring Boot 标准的
 * {@code WebServerInitializedEvent}，使 Spring Cloud 服务注册（Nacos/Eureka/Consul）等组件正确感知服务器就绪。
 * <p>
 * <b>时机</b>：核心服务器在 {@code start()} 绑定端口成功后立即发布 {@link NettyServerStartedEvent}，
 * 本监听器随即在 refresh 阶段发布 Boot 事件，与 Boot {@code WebServerStartStopLifecycle#start()} 的语义一致；
 * 不再延迟到 {@code ApplicationReadyEvent}（那会让服务注册类组件拿到错误的端口或错过注册窗口）。
 * </p>
 * <p>
 * native-image 可达性提示已解耦到 {@link SpringWebRuntimeHintsConfiguration}，本配置被排除时不会连带丢失。
 * </p>
 */
@AutoConfiguration
@AutoConfigureAfter(SpringWebAutoConfiguration.class)
public class WebServerInitializedEventAutoConfiguration {

    @Bean
    public ApplicationListener<NettyServerStartedEvent> webServerInitializedEventPublisher(
            ApplicationContext applicationContext) {
        return event -> {
            NettyHttpServer server = event.getNettyHttpServer();
            if (!server.isRunning()) {
                return;
            }
            WebServer webServer = new PerfWebServer(server.getActualPort(), server);
            applicationContext.publishEvent(new PerfWebServerInitializedEvent(webServer, applicationContext));
        };
    }
}
