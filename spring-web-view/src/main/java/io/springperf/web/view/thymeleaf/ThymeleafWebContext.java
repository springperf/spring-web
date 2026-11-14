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
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Thymeleaf {@link IWebContext} 实现。
 * <p>
 * 变量/区域来自 {@link IContext} 委托；web 能力（session / principal / cookie / contextPath）由 {@link WebExchangeProvider}
 * 按运行环境提供：原生场景为 {@link DefaultWebExchangeProvider}（session 为 null），Servlet 场景由 servlet 模块提供 真实 session / principal。
 * </p>
 *
 * @since 3.5.7
 */
public class ThymeleafWebContext implements IWebContext {

    private final IContext delegate;
    private final IWebExchange exchange;

    /**
     * 使用默认 provider（纯框架，session / principal 为 {@code null}）。
     * <p>
     * 保留此构造以兼容既有调用方；Spring 容器装配场景请用
     * {@link #ThymeleafWebContext(Map, Locale, WebServerHttpRequest, WebServerHttpResponse, WebExchangeProvider)}。
     * </p>
     */
    public ThymeleafWebContext(Map<String, ?> model, Locale locale, WebServerHttpRequest req,
            WebServerHttpResponse resp) {
        this(model, locale, req, resp, new DefaultWebExchangeProvider());
    }

    public ThymeleafWebContext(Map<String, ?> model, Locale locale, WebServerHttpRequest req,
            WebServerHttpResponse resp, WebExchangeProvider provider) {
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
        private final Map<String, ?> model;
        private final IWebSession session;
        private final boolean hasSessionAttributes;

        ThymeleafCoreContext(Locale locale, Map<String, ?> model, IWebExchange exchange) {
            this.locale = locale != null ? locale : Locale.getDefault();
            this.model = model;
            IWebSession currentSession = exchange != null ? exchange.getSession() : null;
            this.session = currentSession;
            this.hasSessionAttributes = currentSession != null && currentSession.exists()
                    && currentSession.getAttributeCount() > 0;
        }

        /**
         * 变量查找：无会话时直接读 model，零复制；有会话时按需合并 session 属性，<b>model 优先</b>。
         * <p>
         * Thymeleaf 3.1 起 {@code #session}/{@code #request} 表达式对象被官方移除 （仅 {@link IWebExchange#getSession()} 等 API
         * 可用但不可直接从模板表达式调用）。为使模板仍能读取会话数据，这里将 session 属性作为上下文变量暴露； 同名时以 model 为准，避免覆盖控制器显式设置的值。
         * </p>
         */
        @Override
        public Locale getLocale() {
            return locale;
        }

        @Override
        public boolean containsVariable(String name) {
            return (model != null && model.containsKey(name))
                    || (hasSessionAttributes && session.containsAttribute(name));
        }

        @Override
        public Set<String> getVariableNames() {
            if (!hasSessionAttributes) {
                // 只读视图：Thymeleaf 仅遍历/查询，避免每请求复制一份 HashMap
                return model != null ? model.keySet() : Collections.emptySet();
            }
            int expectedSize = (model != null ? model.size() : 0) + session.getAttributeCount();
            Set<String> names = new LinkedHashSet<>(expectedSize);
            if (model != null) {
                names.addAll(model.keySet());
            }
            names.addAll(session.getAllAttributeNames());
            return names;
        }

        @Override
        public Object getVariable(String name) {
            if (model != null) {
                Object value = model.get(name);
                // 值为 null 时仍需 containsKey 区分「model 显式置空」与「model 未定义」
                if (value != null || model.containsKey(name)) {
                    return value;
                }
            }
            return hasSessionAttributes ? session.getAttributeValue(name) : null;
        }
    }
}
