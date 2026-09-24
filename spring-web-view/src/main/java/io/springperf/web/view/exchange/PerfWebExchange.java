package io.springperf.web.view.exchange;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.thymeleaf.web.IWebApplication;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.IWebRequest;
import org.thymeleaf.web.IWebSession;

import java.security.Principal;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 基于框架请求/响应的 {@link IWebExchange} 适配（零 Servlet 依赖）。
 * <p>
 * session / principal 在原生场景下为 {@code null}（核心框架无此概念）； Servlet 场景的子类可覆写相应方法补齐。
 * </p>
 *
 * @since 3.5.7
 */
public class PerfWebExchange implements IWebExchange {

    protected final IWebRequest request;
    protected final IWebApplication application;
    protected final String contextPath;
    protected final Map<String, Object> exchangeAttrs = new HashMap<>();
    protected final WebServerHttpRequest nativeReq;

    public PerfWebExchange(WebServerHttpRequest req, WebServerHttpResponse resp) {
        this.nativeReq = req;
        this.contextPath = req.getWebContext().getContextPath();
        this.request = new PerfWebRequest(req, contextPath);
        this.application = new PerfWebApplication();
    }

    @Override
    public IWebRequest getRequest() {
        return request;
    }

    @Override
    public IWebSession getSession() {
        return null;
    }

    @Override
    public IWebApplication getApplication() {
        return application;
    }

    @Override
    public Principal getPrincipal() {
        return null;
    }

    @Override
    public Locale getLocale() {
        return nativeReq.getLocale();
    }

    @Override
    public String getContentType() {
        return null;
    }

    @Override
    public String getCharacterEncoding() {
        return nativeReq.getCharacterEncoding() != null ? nativeReq.getCharacterEncoding().name() : "UTF-8";
    }

    @Override
    public boolean containsAttribute(String name) {
        return exchangeAttrs.containsKey(name);
    }

    @Override
    public int getAttributeCount() {
        return exchangeAttrs.size();
    }

    /**
     * 只读视图缓存：{@code keySet()} 是活视图，包装它不影响后续写入的可见性， 但视图渲染路径会反复调用，逐次新建包装属白扔。
     */
    private final Set<String> allAttributeNamesView = java.util.Collections.unmodifiableSet(exchangeAttrs.keySet());

    @Override
    public Set<String> getAllAttributeNames() {
        return allAttributeNamesView;
    }

    @Override
    public Map<String, Object> getAttributeMap() {
        return exchangeAttrs;
    }

    @Override
    public Object getAttributeValue(String name) {
        return exchangeAttrs.get(name);
    }

    @Override
    public void setAttributeValue(String name, Object value) {
        exchangeAttrs.put(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        exchangeAttrs.remove(name);
    }

    @Override
    public String transformURL(String url) {
        if (url == null)
            return null;
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("//")) {
            return url;
        }
        if (url.startsWith("/")) {
            return contextPath + url;
        }
        return contextPath + "/" + url;
    }
}
