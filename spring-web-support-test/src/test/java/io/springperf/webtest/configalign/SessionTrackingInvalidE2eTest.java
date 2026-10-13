package io.springperf.webtest.configalign;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code server.servlet.session.tracking-modes} 非法值的**启动期失败**契约： 该键由 Spring Boot 的 {@code ServerProperties}
 * 绑定（{@code ignoreInvalidFields=false}）， 非法枚举（如 {@code nope}）必须在应用启动阶段直接失败，而不是静默退化成某个跟踪模式。
 * <p>
 * 由此也可知：框架侧对非法 token 的「逐个跳过」容错解析在 Boot 应用下不可达 （属于非 Boot 裸用法的防御，另有单测覆盖）；E2E 层锁定的行为是 fail-fast。
 * </p>
 */
class SessionTrackingInvalidE2eTest {

    @Test
    void invalidTrackingMode_failsFastOnStartup() {
        // 2.7.x 版本层适配：本用例用裸 SpringApplication 启动，没有 TestContext 的 TestTypeExcludeFilter，
        // 故包内 @TestConfiguration 会被组件扫描到并注册同名 bean（countingWebMetrics）。master（Boot 3.5 /
        // Spring 6）同场景不报错，本分支（Boot 2.7 / Spring 5.3）会先抛 BeanDefinitionOverrideException 掩盖
        // 真正的目标错误 —— 显式允许覆盖，让 tracking-modes 绑定失败如 master 一样浮出。
        Throwable thrown = assertThrows(Throwable.class,
                () -> new SpringApplication(ConfigAlignTestApp.class).run("--server.port=0",
                        "--server.servlet.context-path=/", "--server.servlet.session.tracking-modes=nope",
                        "--spring.main.allow-bean-definition-overriding=true"),
                "非法 tracking-modes 应在启动期失败");

        // 异常链中应能定位到该键（Boot 绑定失败的典型信息），避免被误判为其它启动错误
        StringBuilder chain = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            chain.append(t.getMessage()).append('\n');
        }
        assertTrue(chain.toString().contains("tracking-modes"), "失败信息应指向 tracking-modes 键，实际:\n" + chain);
    }
}
