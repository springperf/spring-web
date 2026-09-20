package io.springperf.web.view.thymeleaf;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.DefaultWebExchangeProvider;
import io.springperf.web.view.WebExchangeProvider;
import org.thymeleaf.context.IContext;
import org.thymeleaf.context.IWebContext;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.IWebSession;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Thymeleaf {@link IWebContext} 实现。
 *
 * <p>变量/区域来自 {@link IContext} 委托；web 能力（session / principal / cookie /
 * contextPath）由 {@link WebExchangeProvider} 按运行环境提供：原生场景为
 * {@link DefaultWebExchangeProvider}（session 为 null），Servlet 场景由 servlet 模块提供
 * 真实 session / principal。</p>
 *
 * @since 3.5.7
 */
public class ThymeleafWebContext implements IWebContext {

    private final IContext delegate;
    private final IWebExchange exchange;

    /**
     * 使用默认 provider（纯框架，session / principal 为 {@code null}）。
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
        this.delegate = new ThymeleafCoreContext(locale, model, this.exchange);
    }

    @Override
    public IWebExchange getExchange() {
        return exchange;
    }

    @Override
    public Locale getLocale() {
        return delegate.getLocale();
    }

    @Override
    public boolean containsVariable(String name) {
        return delegate.containsVariable(name);
    }

    @Override
    public Set<String> getVariableNames() {
        return delegate.getVariableNames();
    }

    @Override
    public Object getVariable(String name) {
        return delegate.getVariable(name);
    }

    private static class ThymeleafCoreContext implements IContext {
        private final Locale locale;
        private final Map<String, Object> variables;

        ThymeleafCoreContext(Locale locale, Map<String, ?> model, IWebExchange exchange) {
            this.locale = locale != null ? locale : Locale.getDefault();
            this.variables = buildVariables(model, exchange);
        }

        /**
         * 合并 session 属性与 model 变量。
         *
         * <p>Thymeleaf 3.1 起 {@code #session}/{@code #request} 表达式对象被官方移除
         * （仅 {@link IWebExchange#getSession()} 等 API 可用但不可直接从模板表达式调用）。
         * 为使模板仍能读取会话数据，这里将 session 属性注入上下文变量；
         * <b>model 优先</b>（同名时以 model 为准），避免覆盖控制器显式设置的值。</p>
         *
         * <p>无会话（原生场景）时零拷贝：直接使用 model 的副本。</p>
         */
        private static Map<String, Object> buildVariables(Map<String, ?> model, IWebExchange exchange) {
            IWebSession session = exchange != null ? exchange.getSession() : null;
            if (session == null || !session.exists() || session.getAttributeCount() == 0) {
                return model != null ? new HashMap<>(model) : Collections.emptyMap();
            }
            Map<String, Object> result = new HashMap<>();
            for (String name : session.getAllAttributeNames()) {
                result.put(name, session.getAttributeValue(name));
            }
            if (model != null) {
                result.putAll(model);
            }
            return result;
        }

        @Override
        public Locale getLocale() { return locale; }

        @Override
        public boolean containsVariable(String name) { return variables.containsKey(name); }

        @Override
        public Set<String> getVariableNames() { return variables.keySet(); }

        @Override
        public Object getVariable(String name) { return variables.get(name); }
    }
}
