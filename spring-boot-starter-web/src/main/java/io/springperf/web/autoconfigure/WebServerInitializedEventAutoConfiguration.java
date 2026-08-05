package io.springperf.web.autoconfigure;

import io.springperf.web.autoconfigure.support.PerfWebServer;
import io.springperf.web.autoconfigure.support.PerfWebServerInitializedEvent;
import io.springperf.web.server.NettyHttpServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * Spring Boot 3 专属：Netty 服务器启动完成后发射 {@link WebServerInitializedEvent}，
 * 使 Spring Cloud 服务注册（Nacos/Eureka/Consul）等组件正确感知服务器就绪。
 *
 * <p>独立的版本适配配置类。Spring Boot 4 移除了
 * {@code org.springframework.boot.web.context.WebServerInitializedEvent}（事件与
 * {@code WebServerApplicationContext} 移到 {@code org.springframework.boot.web.server.context}），
 * 本配置类用字符串形式条件守卫，SB4 下条件不满足、类不加载，避免引用已移除类导致启动失败。
 * SB4 的事件适配见 {@link io.springperf.web.autoconfigure.Boot4WebServerInitializedEventAutoConfiguration}
 * 与 {@link io.springperf.web.autoconfigure.support.Boot4WebServerInitializedEventBridge}。
 *
 * <p>注意：字符串形式的 {@code @ConditionalOnClass} 不触发类加载，仅按名称探测 classpath。
 *
 * <p>同时承载 GraalVM native-image 可达性提示 {@link SpringWebRuntimeHints}：事件路径的
 * JDK 代理 + 反射由 Spring AOT 构建期采集。提示注册也按本类条件走——SB4 下本配置类不加载，
 * registrar 不执行，避免编译期引用 SB3 {@code WebServerApplicationContext} 在 SB4 classpath
 * 缺失时引发类解析失败（SB4 事件走桥接且 native 下不可用，无需提示）。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.springframework.boot.web.context.WebServerInitializedEvent")
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
