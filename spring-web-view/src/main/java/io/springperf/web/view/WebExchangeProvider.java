package io.springperf.web.view;

import io.springperf.web.context.WebComponent;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.thymeleaf.web.IWebExchange;

/**
 * SPI：为模板引擎提供 {@link IWebExchange} 适配。
 *
 * <p>核心框架的 {@link WebServerHttpRequest} 是纯 HTTP 抽象，**没有 session / principal 概念**
 * （这些能力由 Servlet 桥接层提供）。因此模板渲染所需的 web 上下文必须按运行环境选择不同实现：</p>
 * <ul>
 *   <li><b>原生场景</b>（仅 {@code spring-web}）：使用
 *       {@link DefaultWebExchangeProvider}，session / principal 保持 {@code null}；</li>
 *   <li><b>Servlet 场景</b>（引入 {@code spring-web-servlet}）：由 servlet 模块提供
 *       {@code ServletWebExchangeProvider}，桥接真实 {@code HttpSession} / {@code Principal} / Cookie。</li>
 * </ul>
 *
 * <p><b>选择机制</b>：provider 实现 {@link WebComponent}（继承 {@link org.springframework.core.Ordered}），
 * 消费方按 order 升序遍历，取第一个 {@link #supports(WebServerHttpRequest)} 为 {@code true} 的实现。
 * {@link DefaultWebExchangeProvider} 的 order 为 {@link org.springframework.core.Ordered#LOWEST_PRECEDENCE}，
 * 作为兜底始终返回 {@code true}；环境特定的 provider 应给出更小的 order 值以优先命中。</p>
 *
 * <p><b>接入方式</b>：通过 Spring 组件容器注册（{@code WebContext.registerWebComponent}），
 * 消费方用 {@code getWebComponentWithDefault(WebExchangeProvider.class, new DefaultWebExchangeProvider())}
 * 获取。不使用 JDK {@code ServiceLoader}，以完全兼容 GraalVM native-image（AOT 天然支持 Spring 装配）。</p>
 *
 * @since 3.5.7
 * @see DefaultWebExchangeProvider
 */
public interface WebExchangeProvider extends WebComponent {

    /**
     * 当前请求是否可由本 provider 处理。
     *
     * <p>实现应做快速能力探测（如判断请求上是否已存在 Servlet 适配上下文），
     * 不得产生副作用或抛异常。</p>
     *
     * @param request 当前请求
     * @return {@code true} 表示 {@link #createExchange} 可安全调用
     */
    boolean supports(WebServerHttpRequest request);

    /**
     * 创建模板渲染用的 {@link IWebExchange}。
     *
     * <p>仅在 {@link #supports(WebServerHttpRequest)} 返回 {@code true} 时调用。</p>
     *
     * @param request  当前请求
     * @param response 当前响应
     * @return 适配后的 web exchange
     */
    IWebExchange createExchange(WebServerHttpRequest request, WebServerHttpResponse response);

    /**
     * 兜底默认：order 最低，保证任选机制总有实现可用。
     */
    @Override
    default int getOrder() {
        return org.springframework.core.Ordered.LOWEST_PRECEDENCE;
    }
}
