package org.springframework.boot.web.server.context;

import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationEvent;

/**
 * SB4 {@code WebServerInitializedEvent} 的测试桩（仅 test classpath，主代码编译期零引用）。
 *
 * <p>SB4 将该事件从 {@code org.springframework.boot.web.context} 移到
 * {@code org.springframework.boot.web.server.context} 且改为抽象类。本桩与
 * {@link Boot4WebServerInitializedEventBridge} 的 ASM 描述符完全对齐：
 * 抽象方法 {@link #getApplicationContext()} 由桥接生成的子类覆盖，受保护的
 * {@code (WebServer)} 构造器供生成子类 INVOKESPECIAL 调用。</p>
 *
 * <p>SB3.2.12 的 spring-boot jar 不存在本包，测试桩不污染任何真实类。</p>
 */
public abstract class WebServerInitializedEvent extends ApplicationEvent {

    private final WebServer webServer;

    protected WebServerInitializedEvent(WebServer webServer) {
        super(webServer);
        this.webServer = webServer;
    }

    public WebServer getWebServer() {
        return webServer;
    }

    public abstract WebServerApplicationContext getApplicationContext();
}
