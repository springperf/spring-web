package io.springperf.web.autoconfigure;

import io.springperf.web.context.WebContext;
import io.springperf.web.support.view.ServletWebExchangeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Servlet 场景的模板 exchange provider 自动装配（独立配置类）。
 *
 * <p><b>为什么独立成类</b>：{@link ServletWebExchangeProvider} 的方法签名引用
 * {@code org.thymeleaf.web.IWebExchange} 与 {@code io.springperf.web.view.WebExchangeProvider}。
 * 若直接定义在 {@code SpringWebServletAutoConfiguration} 内，Spring 内省该配置类时会解析
 * 其全部 {@code @Bean} 方法签名，导致 classpath 缺少 spring-web-view / Thymeleaf 的模块
 * （如仅引入 servlet 桥接的示例）在条件判断前就抛 {@code NoClassDefFoundError}。
 * 放到独立类上，{@code @ConditionalOnClass} 在类加载前生效，天然隔离。</p>
 *
 * @since 3.5.7
 */
@Configuration
@ConditionalOnClass(name = {
        "io.springperf.web.view.WebExchangeProvider",
        "org.thymeleaf.web.IWebExchange",
        "io.springperf.web.support.servlet.context.ServletAdapterContext"
})
public class SpringWebViewExchangeAutoConfiguration {

    /**
     * Servlet 场景优先命中（order = HIGHEST_PRECEDENCE），
     * 使模板可访问真实 session / principal / cookie。
     */
    @Bean @ConditionalOnMissingBean
    public ServletWebExchangeProvider servletWebExchangeProvider(WebContext webContext) {
        ServletWebExchangeProvider provider = new ServletWebExchangeProvider();
        webContext.registerWebComponent(provider);
        return provider;
    }
}
