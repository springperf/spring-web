package io.springperf.web.view;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * 模板渲染所需的 web 交换对象（Thymeleaf 3.0 形态）。
 *
 * <p><b>为什么不直接用 Thymeleaf 的 {@code org.thymeleaf.web.IWebExchange}</b>：该包是
 * Thymeleaf 3.1 引入的抽象，在 3.0（Spring Boot 2.7 的基线）中不存在；3.0 的
 * {@code org.thymeleaf.context.IWebContext} 直接把 {@code javax.servlet.*} 类型写进方法签名。
 * 因此这里定义框架自有的薄接口，作为模板上下文与运行环境之间的边界：</p>
 * <ul>
 *   <li><b>原生场景</b>（仅 {@code spring-web}）：四个方法均返回 {@code null}，
 *       由 {@link DefaultWebExchangeProvider} 提供；</li>
 *   <li><b>Servlet 场景</b>（引入 {@code spring-web-servlet}）：由 servlet 模块的
 *       {@code ServletWebExchange} 返回真实 {@code HttpServletRequest} /
 *       {@code HttpSession} / {@code Principal}（经 cookie 与会话桥接）。</li>
 * </ul>
 *
 * @since 2.7.7
 * @see WebExchangeProvider
 */
public interface WebExchange {

    /** 当前请求；原生场景为 {@code null}。 */
    HttpServletRequest getRequest();

    /** 当前响应；原生场景为 {@code null}。 */
    HttpServletResponse getResponse();

    /** 当前会话；无会话或原生场景为 {@code null}。 */
    HttpSession getSession();

    /** Servlet 上下文；原生场景为 {@code null}。 */
    ServletContext getServletContext();
}
