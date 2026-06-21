package io.springperf.web.autoconfigure.support;

import io.springperf.web.server.NettyHttpServer;
import lombok.Getter;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.WebServerException;

/**
 * 包装 {@link NettyHttpServer} 适配 Spring Boot 的 {@link WebServer} 接口。
 * <p>使 Spring Cloud 服务注册（Nacos/Eureka/Consul）等依赖 {@link WebServerInitializedEvent}
 * 的组件能够正确获取本框架的服务器信息和端口。</p>
 *
 * @author huangcanda
 * @since 1.0.4
 */
public class PerfWebServer implements WebServer {

    private final int port;
    @Getter
    private final NettyHttpServer nettyHttpServer;

    public PerfWebServer(int port, NettyHttpServer nettyHttpServer) {
        this.port = port;
        this.nettyHttpServer = nettyHttpServer;
    }

    @Override
    public void start() throws WebServerException {
        // Netty 已由 SmartLifecycle 启动，无需额外操作
    }

    @Override
    public void stop() throws WebServerException {
        nettyHttpServer.stop(() -> { });
    }

    @Override
    public int getPort() {
        return port;
    }

}