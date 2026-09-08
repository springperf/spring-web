package io.springperf.web.view.thymeleaf;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.thymeleaf.context.IContext;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Thymeleaf 3.0（SB 2.7）渲染上下文，实现非 web 版 {@link IContext}。
 *
 * <p><b>背景</b>：master 使用 Thymeleaf 3.1 的 {@code org.thymeleaf.web.IWebContext}
 * （{@code getExchange()} 返回瘦接口 {@code IWebExchange}），该包在 Thymeleaf 3.0 中不存在；
 * 3.0 的 {@code IWebContext} 直接耦合 {@code javax.servlet.*}。为保持本模块零 Servlet 依赖，
 * 这里实现纯 {@link IContext}：</p>
 * <ul>
 *   <li>{@code th:text} / {@code ${...}} 等变量表达式完全可用</li>
 *   <li>{@code @{...}} 相对 URL 方言在非 web context 下由
 *       {@code StandardLinkBuilder.processLink} 原样返回（不解析 context path，不抛异常）</li>
 *   <li>不提供 {@code #request}/{@code #session} 等 web 表达式对象</li>
 * </ul>
 */
public class ThymeleafWebContext implements IContext {

    private final Locale locale;
    private final Map<String, Object> variables;

    public ThymeleafWebContext(Map<String, ?> model, Locale locale,
                               WebServerHttpRequest req, WebServerHttpResponse resp) {
        this.locale = locale != null ? locale : Locale.getDefault();
        this.variables = model != null
                ? new LinkedHashMap<>(model)
                : Collections.emptyMap();
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
