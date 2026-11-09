package io.springperf.web.server;

import org.springframework.context.ApplicationEvent;

/**
 * Netty 服务器成功绑定端口并完成启动后发布的框架事件。
 * <p>
 * 对齐 Spring Boot {@code WebServerStartStopLifecycle#start()}：事件在服务器 {@code start()} 内部、
 * 端口绑定成功后立即发布，早于 {@code ContextRefreshedEvent} 与 {@code ApplicationReadyEvent}，
 * 使依赖 {@code WebServerInitializedEvent} 的组件（Spring Cloud 服务注册等）能拿到真实端口。
 * </p>
 * <p>
 * 该事件属于核心模块，不依赖 Spring Boot 的 {@code WebServerInitializedEvent}；由 starter 的
 * {@code WebServerInitializedEventAutoConfiguration} 监听后转换成 Boot 标准事件。
 * </p>
 */
@SuppressWarnings("serial")
public class NettyServerStartedEvent extends ApplicationEvent {

    public NettyServerStartedEvent(NettyHttpServer server) {
        super(server);
    }

    @Override
    public NettyHttpServer getSource() {
        return (NettyHttpServer) super.getSource();
    }

    public NettyHttpServer getNettyHttpServer() {
        return getSource();
    }
}
