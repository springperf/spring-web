package org.springframework.boot.web.server.context;

import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;

/**
 * SB4 {@code WebServerApplicationContext} 的测试桩接口（仅 test classpath）。
 *
 * <p>桥接用 JDK 动态代理把真实 {@link ApplicationContext} 包装为本接口：
 * 代理对 {@link #getWebServer()} / {@link #getServerNamespace()} 特判，其余方法委托
 * 真实上下文。本桩接口成员与桥接代理的分派逻辑一一对应。</p>
 */
public interface WebServerApplicationContext extends ApplicationContext {

    WebServer getWebServer();

    String getServerNamespace();
}
