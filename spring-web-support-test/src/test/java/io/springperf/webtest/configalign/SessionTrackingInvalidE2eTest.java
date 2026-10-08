package io.springperf.webtest.configalign;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code server.servlet.session.tracking-modes} 非法值的**启动期失败**契约： 该键由 Spring Boot 的 {@code ServerProperties}
 * 绑定（{@code ignoreInvalidFields=false}）， 非法枚举（如 {@code nope}）必须在应用启动阶段直接失败，而不是静默退化成某个跟踪模式。
 * <p>
 * 由此也可知：框架侧对非法 token 的「逐个跳过」容错解析在 Boot 应用下不可达 （属于非 Boot 裸用法的防御，另有单测覆盖）；E2E 层锁定的行为是 fail-fast。
 * </p>
 * <p>
 * <b>前置条件</b>：本用例验证的是 <b>Spring Boot 自身 servlet 栈</b>的属性绑定行为，只有在 Servlet 容器存在（{@code ServletWebServerConfiguration}
 * 启用、{@code ServerProperties} 参与绑定）时才成立。 Boot 4 起 {@code spring-boot-starter-web} 不再传递 servlet 容器（如
 * {@code tomcat-embed-core}）， 本仓库测试模块未引入容器，故此处按条件跳过——否则断言会因为「绑压根没发生」而误报为框架缺陷。
 * </p>
 */
class SessionTrackingInvalidE2eTest {

    @Test
    void invalidTrackingMode_failsFastOnStartup() {
        assumeTrue(hasServletContainer(), "需要 Servlet 容器（Boot 的 servlet 自动配置）才会绑定 server.servlet.* 属性");

        Throwable thrown = assertThrows(Throwable.class,
                () -> new SpringApplication(ConfigAlignTestApp.class).run("--server.port=0",
                        "--server.servlet.context-path=/", "--server.servlet.session.tracking-modes=nope"),
                "非法 tracking-modes 应在启动期失败");

        // 异常链中应能定位到该键（Boot 绑定失败的典型信息），避免被误判为其它启动错误
        StringBuilder chain = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append('\n');
        }
        assertTrue(chain.toString().contains("tracking-modes"), "失败信息应指向 tracking-modes 键，实际:\n" + chain);
    }

    /** 探测 Servlet 容器的 WebServerFactory 是否在 classpath 上（Boot 4 起不再是 starter-web 的传递依赖）。 */
    private static boolean hasServletContainer() {
        String[] factories = { "org.springframework.boot.tomcat.TomcatWebServerFactory",
                "org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory",
                "org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory",
                "org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory" };
        for (String f : factories) {
            try {
                Class.forName(f);
                return true;
            } catch (ClassNotFoundException ignored) {
                // 继续探测下一个
            }
        }
        return false;
    }
}
