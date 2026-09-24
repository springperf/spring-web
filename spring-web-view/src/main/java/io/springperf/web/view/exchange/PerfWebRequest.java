package io.springperf.web.view.exchange;

import io.springperf.web.http.WebServerHttpRequest;
import org.springframework.http.HttpHeaders;
import org.thymeleaf.web.IWebRequest;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 基于框架 {@link WebServerHttpRequest} 的 {@link IWebRequest} 适配（零 Servlet 依赖）。
 * <p>
 * Cookie 能力默认空实现；Servlet 场景可覆写 {@link #containsCookie} 等方法补齐。
 * </p>
 *
 * @since 3.5.7
 */
public class PerfWebRequest implements IWebRequest {

    protected final WebServerHttpRequest nativeReq;
    protected final String contextPath;

    public PerfWebRequest(WebServerHttpRequest nativeReq, String contextPath) {
        this.nativeReq = nativeReq;
        this.contextPath = contextPath;
    }

    @Override
    public String getMethod() {
        return nativeReq.getMethodValue();
    }

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
    public String getApplicationPath() {
        return contextPath;
    }

    @Override
    public String getPathWithinApplication() {
        return nativeReq.getPath();
    }

    @Override
    public String getQueryString() {
        // getURI() 由 WebServerHttpRequest 声明为非空（见其 @NonNull 契约）；getRawQuery() 无查询串时为 null
        return nativeReq.getURI().getRawQuery();
    }

    @Override
    public boolean containsHeader(String name) {
        return nativeReq.getHeaders().containsKey(name);
    }

    @Override
    public int getHeaderCount() {
        return nativeReq.getHeaders().size();
    }

    @Override
    public Set<String> getAllHeaderNames() {
        return nativeReq.getHeaders().keySet();
    }

    @Override
    public Map<String, String[]> getHeaderMap() {
        HttpHeaders headers = nativeReq.getHeaders();
        Map<String, String[]> result = new HashMap<>(headers.size());
        for (String key : headers.keySet()) {
            List<String> values = headers.get(key);
            result.put(key, values != null ? values.toArray(new String[0]) : new String[0]);
        }
        return result;
    }

    @Override
    public String[] getHeaderValues(String name) {
        List<String> values = nativeReq.getHeaders().get(name);
        return values != null ? values.toArray(new String[0]) : new String[0];
    }

    @Override
    public boolean containsParameter(String name) {
        return nativeReq.getParameterMap().containsKey(name);
    }

    @Override
    public int getParameterCount() {
        return nativeReq.getParameterMap().size();
    }

    @Override
    public Set<String> getAllParameterNames() {
        return nativeReq.getParameterMap().keySet();
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        Map<String, String[]> map = new HashMap<>();
        for (Map.Entry<String, List<String>> entry : nativeReq.getParameterMap().entrySet()) {
            List<String> values = entry.getValue();
            map.put(entry.getKey(), values != null ? values.toArray(new String[0]) : new String[0]);
        }
        return map;
    }

    @Override
    public String[] getParameterValues(String name) {
        List<String> values = nativeReq.getParameterMap().get(name);
        return values != null ? values.toArray(new String[0]) : new String[0];
    }

    @Override
    public boolean containsCookie(String name) {
        return false;
    }

    @Override
    public int getCookieCount() {
        return 0;
    }

    @Override
    public Set<String> getAllCookieNames() {
        return Collections.emptySet();
    }

    @Override
    public Map<String, String[]> getCookieMap() {
        return Collections.emptyMap();
    }

    @Override
    public String[] getCookieValues(String name) {
        return new String[0];
    }
}
