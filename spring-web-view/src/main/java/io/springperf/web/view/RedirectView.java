package io.springperf.web.view;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class RedirectView implements View {

    private final String redirectUrl;
    private final HttpStatusCode status;

    public RedirectView(String redirectUrl) {
        this(redirectUrl, HttpStatus.FOUND);
    }

    /** 允许调用方（如 ModelAndView）指定 301/307 等重定向状态码，覆盖默认的 302。 */
    public RedirectView(String redirectUrl, HttpStatusCode status) {
        this.redirectUrl = redirectUrl;
        this.status = status != null ? status : HttpStatus.FOUND;
    }

    @Override
    public void render(Map<String, ?> model, WebServerHttpRequest req, WebServerHttpResponse resp) throws Exception {
        resp.setStatusCode(status);
        String location = buildLocation(redirectUrl, req);
        String query = buildQueryString(model);
        if (!query.isEmpty()) {
            // 目标已含 query（redirect:/login?x=1）时用 & 续接，避免双 ? 丢失后续参数
            location += (location.indexOf('?') >= 0 ? "&" : "?") + query;
        }
        resp.getHeaders().set(HttpHeaders.LOCATION, location);
    }

    private static String buildLocation(String url, WebServerHttpRequest req) {
        if (isExternalTarget(url)) {
            Set<String> allowedHosts = allowedHosts(req);
            if (!allowedHosts.isEmpty() && !isAllowedHost(url, allowedHosts)) {
                throw new IllegalArgumentException("Redirect target host is not allowed by '"
                        + ViewProperties.REDIRECT_HOSTS + "': " + url);
            }
            return url;
        }
        String cp = req.getWebContext().getContextPath();
        if (url.startsWith("/")) {
            return cp + url;
        }
        return cp + "/" + url;
    }

    /** http(s) 绝对 URL 与 {@code //host} 协议相对 URL 都是外部目标；其余一律按站内相对路径处理。 */
    private static boolean isExternalTarget(String url) {
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("//");
    }

    /** 未配置白名单时不限制外部目标，保持既有行为；配置后仅放行列表内的 host。 */
    private static Set<String> allowedHosts(WebServerHttpRequest req) {
        WebContext wc = req.getWebContext();
        ApplicationProperties props = wc != null ? wc.getProps() : null;
        String raw = props != null ? props.get(ViewProperties.REDIRECT_HOSTS, null) : null;
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> hosts = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String host = item.trim().toLowerCase(Locale.ROOT);
            if (!host.isEmpty()) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    private static boolean isAllowedHost(String url, Set<String> allowedHosts) {
        String host = extractHost(url);
        return host != null && allowedHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    private static String extractHost(String url) {
        try {
            // 协议相对 URL 需补 scheme 才能被 URI 解析
            URI uri = URI.create(url.startsWith("//") ? "https:" + url : url);
            return uri.getHost();
        } catch (IllegalArgumentException ex) {
            // 畸形 URL 一律视为不允许，避免绕过白名单
            return null;
        }
    }

    private static String buildQueryString(Map<String, ?> model) {
        if (model == null || model.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> entry : model.entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (!isSimpleType(value.getClass())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("&");
            }
            sb.append(encode(entry.getKey())).append("=").append(encode(value.toString()));
        }
        return sb.toString();
    }

    private static boolean isSimpleType(Class<?> type) {
        return type == String.class || type == Integer.class || type == Long.class || type == Double.class
                || type == Float.class || type == Boolean.class || type == Short.class || type == Byte.class
                || type == Character.class || type.isPrimitive();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}
