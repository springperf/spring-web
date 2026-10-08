package io.springperf.web.autoconfigure;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.support.view.ServletWebExchangeProvider;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SpringWebViewExchangeAutoConfiguration} 的条件装配契约。
 * <p>
 * <b>为什么值得单测</b>：该类的<b>存在理由</b>就是防一个具体的启动崩溃 —— {@code ServletWebExchangeProvider} 的方法签名引用
 * {@code org.thymeleaf.web.IWebExchange} 与 {@code io.springperf.web.view.WebExchangeProvider}，若把这个 {@code @Bean} 直接定义在
 * {@code SpringWebServletAutoConfiguration} 内，Spring 内省配置类时会解析全部 {@code @Bean} 方法签名， 导致<b>仅引入 servlet 桥接、未引入 view /
 * Thymeleaf 的应用在条件判断前就抛 {@code NoClassDefFoundError}</b>。独立成类 + {@code @ConditionalOnClass} 是修复手段。
 * </p>
 * <p>
 * <b>本模块的测试 classpath 恰好就是「缺 Thymeleaf」的真实场景</b>（{@code spring-web-view} 为 {@code provided}，且其自身不带
 * Thymeleaf），因此这里能直接断言此前无法覆盖的负路径： 缺类时必须「干净地不加载」，而不是启动失败。 正路径（三类齐备 → provider 注册）由 E2E {@code ThymeleafSessionE2eTest}
 * 覆盖。
 * </p>
 */
class SpringWebViewExchangeAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WebContextConfig.class)
            .withConfiguration(AutoConfigurations.of(SpringWebViewExchangeAutoConfiguration.class));

    /** 提供该配置类 {@code @Bean} 方法所需的 WebContext。 */
    @Configuration(proxyBeanMethods = false)
    static class WebContextConfig {
        @Bean
        WebContext webContext() {
            ApplicationProperties props = new ApplicationProperties();
            props.setEnvironment(new MockEnvironment());
            WebContext ctx = new WebContext(new DispatcherHandler(), props);
            ctx.setApplicationContext(new StaticApplicationContext());
            return ctx;
        }
    }

    /**
     * 核心契约：类缺失时必须「不加载」而非「启动失败」。
     * <p>
     * 这正是「拆成独立配置类」所换来的性质 —— 修复前该崩溃表现为 {@code NoClassDefFoundError}， 且发生在 {@code @ConditionalOnClass}
     * 的判断<b>之前</b>（Spring 内省方法签名时）。
     * </p>
     */
    @Test
    void thymeleafAndViewMissing_contextStillStarts() {
        // 本模块测试 classpath 天然缺 org.thymeleaf.web.IWebExchange 与 io.springperf.web.view.WebExchangeProvider
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ServletWebExchangeProvider.class);
        });
    }

    @Test
    void missingThymeleafClass_doesNotLoadConfig() {
        runner.withClassLoader(new FilteredClassLoader("org.thymeleaf.web.IWebExchange")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ServletWebExchangeProvider.class);
        });
    }

    @Test
    void missingViewProviderClass_doesNotLoadConfig() {
        runner.withClassLoader(new FilteredClassLoader("io.springperf.web.view.WebExchangeProvider")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ServletWebExchangeProvider.class);
        });
    }

    @Test
    void missingServletAdapterContextClass_doesNotLoadConfig() {
        runner.withClassLoader(
                new FilteredClassLoader("io.springperf.web.support.servlet.context.ServletAdapterContext"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ServletWebExchangeProvider.class);
                });
    }

    /**
     * 三类齐备时注册 provider 的**正路径**不在本模块验证。
     * <p>
     * 原因（本轮实测）：本模块测试 classpath 无 Thymeleaf，直接 {@code new ServletWebExchangeProvider()} 会抛
     * {@code NoClassDefFoundError: org/thymeleaf/web/IWebExchange} —— 这正是该类注释所描述的 崩溃机制的实证。正路径由 E2E
     * {@code ThymeleafSessionE2eTest} 覆盖（那里三类齐备）， 本类只负责把「缺类时不崩溃」的负路径契约钉住。
     * </p>
     */
    @Test
    void beanInstantiation_withoutThymeleaf_failsFast_notSilently() {
        // 记录用：证明「provider 无法在缺 Thymeleaf 的 classpath 上被实例化」——
        // 这就是 @ConditionalOnClass 必须在类加载前生效（而非方法调用时）的根本原因。
        assertThatThrownBy(() -> new ServletWebExchangeProvider()).isInstanceOf(NoClassDefFoundError.class)
                .hasMessageContaining("thymeleaf");
    }
}
