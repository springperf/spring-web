package io.springperf.web.view;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class RedirectView implements View {

    /**
     * 白名单配置串 → 解析后的 host 集合。
     * <p>
     * 解析结果只依赖配置串本身，与请求无关，因此可跨请求、跨实例复用。以配置串为键也天然支持配置热更新：值变了就是另一个键，重新解析；旧键留着也不影响正确性。
     * </p>
     */
    private static final ConcurrentMap<String, Set<String>> ALLOWED_HOSTS_CACHE = new ConcurrentHashMap<>();

    /** 正常只有一个取值；上限仅防御「按租户/环境拼接出大量不同配置串」导致的无界增长，超出后不缓存但仍正确解析。 */
    private static final int MAX_CACHED_ALLOWED_HOSTS = 8;

    private final String redirectUrl;
    private final HttpStatus status;

    public RedirectView(String redirectUrl) {
        this(redirectUrl, HttpStatus.FOUND);
    }

    /** 允许调用方（如 ModelAndView）指定 301/307 等重定向状态码，覆盖默认的 302。 */
    public RedirectView(String redirectUrl, HttpStatus status) {
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
        if (raw == null || isBlank(raw)) {
            return Collections.emptySet();
        }
        Set<String> cached = ALLOWED_HOSTS_CACHE.get(raw);
        if (cached != null) {
            return cached;
        }
        Set<String> parsed = parseAllowedHosts(raw);
        if (parsed.isEmpty()) {
            // 只含空白项（如 ","）时等价于未配置，不必占用缓存
            return Collections.emptySet();
        }
        Set<String> hosts = Collections.unmodifiableSet(parsed);
        if (ALLOWED_HOSTS_CACHE.size() >= MAX_CACHED_ALLOWED_HOSTS) {
            return hosts;
        }
        Set<String> winner = ALLOWED_HOSTS_CACHE.putIfAbsent(raw, hosts);
        return winner != null ? winner : hosts;
    }

    private static Set<String> parseAllowedHosts(String raw) {
        Set<String> hosts = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String host = item.trim().toLowerCase(Locale.ROOT);
            if (!host.isEmpty()) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    /**
     * 手写判空：{@code trim()} 在存在首尾空白时会新建字符串，而这里每请求都要判断一次。
     * <p>
     * 判定范围与 {@link String#trim()} 保持一致（只把 {@code <= '\u0020'} 视为空白），避免出现「某些不可见字符以前算配置、现在算未配置」的行为漂移。
     * </p>
     */
    private static boolean isBlank(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > ' ') {
                return false;
            }
        }
        return true;
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
        return type == String.class || type == Integer.class || type == Long.class
                || type == Double.class || type == Float.class || type == Boolean.class
                || type == Short.class || type == Byte.class || type == Character.class
                || type.isPrimitive();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}