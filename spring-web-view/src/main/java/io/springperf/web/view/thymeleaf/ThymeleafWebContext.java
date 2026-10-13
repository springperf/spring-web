package io.springperf.web.view.thymeleaf;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.DefaultWebExchangeProvider;
import io.springperf.web.view.WebExchange;
import io.springperf.web.view.WebExchangeProvider;
import org.thymeleaf.context.IWebContext;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Thymeleaf 3.0（Spring Boot 2.7 基线）的 {@link IWebContext} 实现。
 *
 * <p><b>与 master 的差异</b>：master 使用 Thymeleaf 3.1 的
 * {@code org.thymeleaf.context.IWebContext}（多一层 {@code getExchange()} → 瘦接口
 * {@code IWebExchange}），该包在 3.0 中不存在；3.0 的 {@code IWebContext} 直接暴露
 * {@code javax.servlet.*}。因此这里改为：变量/区域沿用 {@link org.thymeleaf.context.IContext}
 * 的本地实现，web 能力（request / response / session / ServletContext）委托给
 * {@link WebExchangeProvider} 提供的 {@link WebExchange}。</p>
 *
 * <ul>
 *   <li>原生场景（仅 {@code spring-web}）：{@link DefaultWebExchangeProvider} 提供空实现，
 *       三个 web 对象为 {@code null}，不提供 {@code #request}/{@code #session} 表达式对象；</li>
 *   <li>Servlet 场景（引入 {@code spring-web-servlet}）：由 {@code ServletWebExchangeProvider}
 *       提供真实 session / principal，{@code @{...}} 亦按真实 context path 解析。</li>
 * </ul>
 *
 * @since 2.7.7
 */
public class ThymeleafWebContext implements IWebContext {

    private final Locale locale;
    private final Map<String, Object> variables;
    private final WebExchange exchange;

    /**
     * 使用默认 provider（纯框架，session / principal 为 {@code null}）。
     *
     * <p>保留此构造以兼容既有调用方；Spring 容器装配场景请用
     * {@link #ThymeleafWebContext(Map, Locale, WebServerHttpRequest, WebServerHttpResponse, WebExchangeProvider)}。</p>
     */
    public ThymeleafWebContext(Map<String, ?> model, Locale locale,
                               WebServerHttpRequest req, WebServerHttpResponse resp) {
        this(model, locale, req, resp, new DefaultWebExchangeProvider());
    }

    public ThymeleafWebContext(Map<String, ?> model, Locale locale,
                               WebServerHttpRequest req, WebServerHttpResponse resp,
                               WebExchangeProvider provider) {
        WebExchangeProvider p = provider != null ? provider : new DefaultWebExchangeProvider();
        this.exchange = p.createExchange(req, resp);
        this.locale = locale != null ? locale : Locale.getDefault();
        this.variables = model != null
                ? new LinkedHashMap<>(model)
                : Collections.emptyMap();
    }

    @Override
    public HttpServletRequest getRequest() {
        return exchange.getRequest();
    }

    @Override
    public HttpServletResponse getResponse() {
        return exchange.getResponse();
    }

    @Override
    public HttpSession getSession() {
        return exchange.getSession();
    }

    @Override
    public ServletContext getServletContext() {
        return exchange.getServletContext();
    }

    @Override
    public Locale getLocale() {
        return locale;
    }

    @Override
    public boolean containsVariable(String name) {
        return variables.containsKey(name);
    }

    @Override
    public Set<String> getVariableNames() {
        return variables.keySet();
    }

    @Override
    public Object getVariable(String name) {
        return variables.get(name);
    }
}
