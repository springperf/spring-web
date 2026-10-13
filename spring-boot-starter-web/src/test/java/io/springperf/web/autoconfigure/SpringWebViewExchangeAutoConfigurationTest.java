package io.springperf.web.autoconfigure;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.DispatcherHandler;
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

/**
 * {@link SpringWebViewExchangeAutoConfiguration} 的条件装配契约。
 * <p>
 * <b>为什么值得单测</b>：该类的<b>存在理由</b>就是防一个具体的启动崩溃 ——
 * {@code ServletWebExchangeProvider} 的方法签名引用了 {@code io.springperf.web.view.WebExchangeProvider} /
 * {@code WebExchange} 与 servlet 适配上下文，若把这个 {@code @Bean} 直接定义在
 * {@code SpringWebServletAutoConfiguration} 内，Spring 内省配置类时会解析全部 {@code @Bean} 方法签名，
 * 导致<b>缺少 spring-web-view 的应用（如仅引入 servlet 桥接的示例）在条件判断前就抛
 * {@code NoClassDefFoundError}</b>。独立成类 + {@code @ConditionalOnClass} 是修复手段。
 * </p>
 * <p>
 * <b>与 master 的差异（适配，非漏改）</b>：
 * </p>
 * <ol>
 * <li>master 的守卫是 <b>3 项</b>，含 {@code org.thymeleaf.web.IWebExchange}；本分支
 * {@code ServletWebExchangeProvider} 的方法签名里<b>不含任何 Thymeleaf 类型</b> —— Thymeleaf 3.0 没有
 * {@code org.thymeleaf.web.IWebExchange}（由 {@code ThymeleafWebContext} 在 3.0 的
 * {@code org.thymeleaf.context.IWebContext} 上适配），故本分支的守卫只需
 * {@code WebExchangeProvider} + {@code ServletAdapterContext} 两项。</li>
 * <li>连带地，master 两条「缺 Thymeleaf 类 → 配置不加载」的用例在本分支<b>语义不成立</b>：那两类在本模块测试
 * classpath 本就齐备（{@code spring-web-view} / {@code spring-web-servlet} 为 {@code provided}），
 * 且对不存在的类名做 {@code FilteredClassLoader} 是空操作（属无意义断言）。故改为对<b>真实存在的两个守卫类</b>
 * 逐个做负路径验证；master 那条「类缺失时直接 {@code new ServletWebExchangeProvider()} 抛
 * {@code NoClassDefFoundError}」同理不适用（本分支 provider 不引用 Thymeleaf）。</li>
 * <li><b>补偿</b>：master 的正路径（依赖齐备 → 注册 provider）交给 E2E {@code ThymeleafSessionE2eTest}
 * 覆盖，而该 E2E 尚未搬入本分支 → 这里直接断言正路径（Bean 存在 + 已注册进 {@code WebContext} 组件容器）。</li>
 * </ol>
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
     * 核心契约：view 模块缺失时必须「不加载」而非「启动失败」。
     * <p>
     * 这正是「拆成独立配置类」所换来的性质 —— 修复前该崩溃表现为 {@code NoClassDefFoundError}，
     * 且发生在 {@code @ConditionalOnClass} 的判断<b>之前</b>（Spring 内省方法签名时）。
     * </p>
     */
    @Test
    void viewProviderClassMissing_configNotLoaded_contextStillStarts() {
        runner.withClassLoader(new FilteredClassLoader("io.springperf.web.view.WebExchangeProvider")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ServletWebExchangeProvider.class);
        });
    }

    /** 同上：servlet 适配上下文缺失时（未引入 spring-web-servlet）。 */
    @Test
    void servletAdapterContextClassMissing_configNotLoaded_contextStillStarts() {
        runner.withClassLoader(
                new FilteredClassLoader("io.springperf.web.support.servlet.context.ServletAdapterContext"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ServletWebExchangeProvider.class);
                });
    }

    /**
     * 正路径：两类齐备 → 注册 provider，且已挂进 {@code WebContext} 组件容器。
     * <p>
     * 注册动作是该 {@code @Bean} 方法的职责（模板渲染按 order 从容器取用，见
     * {@code WebExchangeProvider} 的「选择机制」说明），仅断言「Bean 存在」会漏掉这一半契约。
     * </p>
     */
    @Test
    void classesPresent_registersProviderIntoWebContextContainer() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ServletWebExchangeProvider.class);

            ServletWebExchangeProvider bean = context.getBean(ServletWebExchangeProvider.class);
            assertThat(context.getBean(WebContext.class).getWebComponent(ServletWebExchangeProvider.class))
                    .as("@Bean 方法应把 provider 注册进 WebContext 组件容器")
                    .isSameAs(bean);
        });
    }
}
