package io.springperf.web.support.view;

import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.view.exchange.PerfWebRequest;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@link org.thymeleaf.web.IWebRequest} 的 Servlet 适配。
 * <p>
 * 在 {@link PerfWebRequest} 的框架能力之上，补齐 Cookie 支持 （原生场景下 Cookie 恒为空）。Cookie 在首次访问时惰性解析并缓存， 避免模板多次读取造成重复遍历。
 * </p>
 *
 * @since 3.5.7
 */
public class ServletWebRequest extends PerfWebRequest {

    private final HttpServletRequest servletRequest;
    private Map<String, String[]> cookieCache;

    public ServletWebRequest(WebServerHttpRequest nativeReq, String contextPath, HttpServletRequest servletRequest) {
        super(nativeReq, contextPath);
        this.servletRequest = servletRequest;
    }

    /**
     * servlet 场景下从 {@link HttpServletRequest} 取真实端口，覆盖基类基于 URI 的推断 （URI 无显式端口时基类返回 80，HTTPS / 非标准端口下不准确）。
     */
    @Override
    public Integer getServerPort() {
        int port = servletRequest.getServerPort();
        // 不用三元式：int 与 Integer 混用会把 super 的结果拆箱再装箱（BX_UNBOXING_IMMEDIATELY_REBOXED），
        // 且 super 返回 null 时会直接 NPE
        if (port > 0) {
            return port;
        }
        return super.getServerPort();
    }

    /**
     * servlet 场景下从 {@link HttpServletRequest} 取真实主机名，覆盖基类基于 URI 的推断。
     */
    @Override
    public String getServerName() {
        String name = servletRequest.getServerName();
        return name != null && !name.isEmpty() ? name : super.getServerName();
    }

    /**
     * servlet 场景下从 {@link HttpServletRequest} 取真实 scheme（含反向代理 / TLS 终止场景）。
     */
    @Override
    public String getScheme() {
        String scheme = servletRequest.getScheme();
        return scheme != null && !scheme.isEmpty() ? scheme : super.getScheme();
    }

    private Map<String, String[]> cookies() {
        Map<String, String[]> cached = this.cookieCache;
        if (cached != null) {
            return cached;
        }
        Cookie[] cookies = servletRequest.getCookies();
        if (cookies == null || cookies.length == 0) {
            this.cookieCache = Collections.emptyMap();
            return this.cookieCache;
        }
        Map<String, java.util.List<String>> grouped = new LinkedHashMap<>();
        for (Cookie cookie : cookies) {
            if (cookie.getName() == null) {
                continue;
            }
            grouped.computeIfAbsent(cookie.getName(), k -> new java.util.ArrayList<>()).add(cookie.getValue());
        }
        Map<String, String[]> result = new HashMap<>(grouped.size());
        for (Map.Entry<String, java.util.List<String>> e : grouped.entrySet()) {
            result.put(e.getKey(), e.getValue().toArray(new String[0]));
        }
        this.cookieCache = result;
        return result;
    }

    @Override
    public boolean containsCookie(String name) {
        return cookies().containsKey(name);
    }

    @Override
    public int getCookieCount() {
        return cookies().size();
    }

    @Override
    public Set<String> getAllCookieNames() {
        return cookies().keySet();
    }

    @Override
    public Map<String, String[]> getCookieMap() {
        return cookies();
    }

    @Override
    public String[] getCookieValues(String name) {
        String[] values = cookies().get(name);
        return values != null ? values : new String[0];
    }
}
