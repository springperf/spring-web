package io.springperf.web.view.thymeleaf;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.http.HttpHeaders;
import org.thymeleaf.context.IContext;
import org.thymeleaf.context.IWebContext;
import org.thymeleaf.web.IWebApplication;
import org.thymeleaf.web.IWebExchange;
import org.thymeleaf.web.IWebRequest;
import org.thymeleaf.web.IWebSession;

import java.io.InputStream;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ThymeleafWebContext implements IWebContext {

    private final IContext delegate;
    private final IWebExchange exchange;

    public ThymeleafWebContext(Map<String, ?> model, Locale locale,
                               WebServerHttpRequest req, WebServerHttpResponse resp) {
        this.delegate = new ThymeleafCoreContext(locale, model);
        this.exchange = new PerfWebExchange(req, resp);
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

        ThymeleafCoreContext(Locale locale, Map<String, ?> variables) {
            this.locale = locale != null ? locale : Locale.getDefault();
            this.variables = new HashMap<>(variables);
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

    private static class PerfWebExchange implements IWebExchange {
        private final PerfWebRequest request;
        private final PerfWebApplication application;
        private final String contextPath;
        private final Map<String, Object> exchangeAttrs = new HashMap<>();

        PerfWebExchange(WebServerHttpRequest req, WebServerHttpResponse resp) {
            this.contextPath = req.getWebContext().getContextPath();
            this.request = new PerfWebRequest(req, contextPath);
            this.application = new PerfWebApplication();
        }

        @Override
        public IWebRequest getRequest() { return request; }

        @Override
        public IWebSession getSession() { return null; }

        @Override
        public IWebApplication getApplication() { return application; }

        @Override
        public Principal getPrincipal() { return null; }

        @Override
        public Locale getLocale() { return request.nativeReq.getLocale(); }

        @Override
        public String getContentType() { return null; }

        @Override
        public String getCharacterEncoding() {
            return request.nativeReq.getCharacterEncoding() != null
                    ? request.nativeReq.getCharacterEncoding().name() : "UTF-8";
        }

        @Override
        public boolean containsAttribute(String name) { return exchangeAttrs.containsKey(name); }

        @Override
        public int getAttributeCount() { return exchangeAttrs.size(); }

        @Override
        public Set<String> getAllAttributeNames() { return exchangeAttrs.keySet(); }

        @Override
        public Map<String, Object> getAttributeMap() { return exchangeAttrs; }

        @Override
        public Object getAttributeValue(String name) { return exchangeAttrs.get(name); }

        @Override
        public void setAttributeValue(String name, Object value) { exchangeAttrs.put(name, value); }

        @Override
        public void removeAttribute(String name) { exchangeAttrs.remove(name); }

        @Override
        public String transformURL(String url) {
            if (url == null) return null;
            if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("//")) {
                return url;
            }
            if (url.startsWith("/")) {
                return contextPath + url;
            }
            return contextPath + "/" + url;
        }
    }

    private static class PerfWebRequest implements IWebRequest {
        private final WebServerHttpRequest nativeReq;
        private final String contextPath;

        PerfWebRequest(WebServerHttpRequest nativeReq, String contextPath) {
            this.nativeReq = nativeReq;
            this.contextPath = contextPath;
        }

        @Override
        public String getMethod() { return nativeReq.getMethodValue(); }

        @Override
        public String getScheme() {
            URI uri = nativeReq.getURI();
            return uri != null && uri.getScheme() != null ? uri.getScheme() : "http";
        }

        @Override
        public String getServerName() {
            URI uri = nativeReq.getURI();
            return uri != null && uri.getHost() != null ? uri.getHost() : "localhost";
        }

        @Override
        public Integer getServerPort() {
            URI uri = nativeReq.getURI();
            return uri != null && uri.getPort() > 0 ? uri.getPort() : 80;
        }

        @Override
        public String getApplicationPath() { return contextPath; }

        @Override
        public String getPathWithinApplication() { return nativeReq.getPath(); }

        @Override
        public String getQueryString() {
            URI uri = nativeReq.getURI();
            return uri != null ? uri.getRawQuery() : null;
        }

        @Override
        public boolean containsHeader(String name) {
            return nativeReq.getHeaders().containsKey(name);
        }

        @Override
        public int getHeaderCount() { return nativeReq.getHeaders().size(); }

        @Override
        public Set<String> getAllHeaderNames() { return nativeReq.getHeaders().keySet(); }

        @Override
        public Map<String, String[]> getHeaderMap() {
            HttpHeaders headers = nativeReq.getHeaders();
            Map<String, String[]> result = new HashMap<>(headers.size());
            for (String key : headers.keySet()) {
                java.util.List<String> values = headers.get(key);
                result.put(key, values != null ? values.toArray(new String[0]) : new String[0]);
            }
            return result;
        }

        @Override
        public String[] getHeaderValues(String name) {
            java.util.List<String> values = nativeReq.getHeaders().get(name);
            return values != null ? values.toArray(new String[0]) : new String[0];
        }

        @Override
        public boolean containsParameter(String name) {
            return nativeReq.getParameterMap().containsKey(name);
        }

        @Override
        public int getParameterCount() { return nativeReq.getParameterMap().size(); }

        @Override
        public Set<String> getAllParameterNames() { return nativeReq.getParameterMap().keySet(); }

        @Override
        public Map<String, String[]> getParameterMap() {
            Map<String, String[]> map = new HashMap<>();
            for (Map.Entry<String, java.util.List<String>> entry : nativeReq.getParameterMap().entrySet()) {
                java.util.List<String> values = entry.getValue();
                map.put(entry.getKey(), values != null ? values.toArray(new String[0]) : new String[0]);
            }
            return map;
        }

        @Override
        public String[] getParameterValues(String name) {
            java.util.List<String> values = nativeReq.getParameterMap().get(name);
            return values != null ? values.toArray(new String[0]) : new String[0];
        }

        @Override
        public boolean containsCookie(String name) { return false; }

        @Override
        public int getCookieCount() { return 0; }

        @Override
        public Set<String> getAllCookieNames() { return Collections.emptySet(); }

        @Override
        public Map<String, String[]> getCookieMap() { return Collections.emptyMap(); }

        @Override
        public String[] getCookieValues(String name) { return new String[0]; }
    }

    private static class PerfWebApplication implements IWebApplication {
        private final Map<String, Object> attrs = new HashMap<>();

        @Override
        public boolean containsAttribute(String name) { return attrs.containsKey(name); }

        @Override
        public int getAttributeCount() { return attrs.size(); }

        @Override
        public Set<String> getAllAttributeNames() { return attrs.keySet(); }

        @Override
        public Map<String, Object> getAttributeMap() { return attrs; }

        @Override
        public Object getAttributeValue(String name) { return attrs.get(name); }

        @Override
        public void setAttributeValue(String name, Object value) { attrs.put(name, value); }

        @Override
        public void removeAttribute(String name) { attrs.remove(name); }

        @Override
        public boolean resourceExists(String path) {
            return getClass().getClassLoader().getResource(path) != null;
        }

        @Override
        public InputStream getResourceAsStream(String path) {
            return getClass().getClassLoader().getResourceAsStream(path);
        }
    }
}