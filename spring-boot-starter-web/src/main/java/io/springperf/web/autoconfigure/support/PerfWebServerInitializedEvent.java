package io.springperf.web.autoconfigure.support;

import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 本框架的 {@link WebServerInitializedEvent} 实现。
 * <p>当 {@link io.springperf.web.server.NettyHttpServer} 绑定端口成功后发射，
 * 使 Spring Cloud 服务注册（Nacos/Eureka/Consul）等监听此事件的组件能正常工作。</p>
 *
 * <p>由于本框架使用 {@code AnnotationConfigApplicationContext}（非 {@code WebServerApplicationContext}），
 * 通过 JDK 动态代理将实际上下文包装为 {@link WebServerApplicationContext}，仅覆盖
 * {@link WebServerApplicationContext#getWebServer()} 返回本框架的 WebServer 实现。</p>
 *
 * @author huangcanda
 * @since 1.0.4
 */
public class PerfWebServerInitializedEvent extends WebServerInitializedEvent {

    private final WebServerApplicationContext webServerApplicationContext;

    public PerfWebServerInitializedEvent(WebServer webServer, ApplicationContext applicationContext) {
        super(webServer);
        this.webServerApplicationContext = createProxy(webServer, applicationContext);
    }

    @Override
    public WebServerApplicationContext getApplicationContext() {
        return webServerApplicationContext;
    }

    /**
     * 通过 JDK 动态代理将普通 ApplicationContext 包装为 WebServerApplicationContext。
     * <p>所有方法委托给真实的 ApplicationContext，仅 {@code getWebServer()} 返回给定的 WebServer。</p>
     */
    private static WebServerApplicationContext createProxy(WebServer webServer, ApplicationContext delegate) {
        return (WebServerApplicationContext) Proxy.newProxyInstance(
                WebServerApplicationContext.class.getClassLoader(),
                new Class<?>[]{WebServerApplicationContext.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ("getWebServer".equals(method.getName()) && method.getParameterCount() == 0) {
                            return webServer;
                        }
                        if ("getServerNamespace".equals(method.getName()) && method.getParameterCount() == 0) {
                            return null;
                        }
                        // equals/hashCode/toString 也委托给真实上下文
                        return method.invoke(delegate, args);
                    }
                }
        );
    }

}