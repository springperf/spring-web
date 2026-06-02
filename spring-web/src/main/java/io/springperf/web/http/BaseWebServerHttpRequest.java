package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportUtils;
import org.springframework.http.server.ServerHttpAsyncRequestControl;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.MultiValueMap;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public abstract class BaseWebServerHttpRequest implements WebServerHttpRequest, RequestContext {

    protected final WebContext webContext;
    private final String uriStrWithQuery;
    private final String uriStr;
    private final String path;
    private MultiValueMap<String, String> parameterMap;
    private Charset characterEncoding = StandardCharsets.UTF_8;
    private List<Locale> locales;
    protected final Map<String, Object> attributes = new ConcurrentHashMap<>();
    protected final Object[] fastAttributes = new Object[RequestAttribute.getMaxSize()];
    protected int filterIndex = 0;
    private boolean lifecycleCompleted;

    protected BaseWebServerHttpRequest(WebContext webContext, String uriStrWithQuery, String resolvedPath) {
        this.webContext = webContext;
        this.uriStrWithQuery = uriStrWithQuery;
        int queryIndex = uriStrWithQuery.indexOf('?');
        this.uriStr = queryIndex == -1 ? uriStrWithQuery : uriStrWithQuery.substring(0, queryIndex);
        this.path = resolvedPath;
    }

    public String getUriStrWithQuery() { return uriStrWithQuery; }
    public String getUriStr() { return uriStr; }
    public String getPath() { return path; }

    public MultiValueMap<String, String> getParameterMap() {
        if (parameterMap == null) parameterMap = parseParameters();
        return parameterMap;
    }

    public Map<String, String[]> getParameterMapArray() {
        MultiValueMap<String, String> pm = getParameterMap();
        Map<String, String[]> arr = new HashMap<>();
        for (String key : pm.keySet()) arr.put(key, pm.get(key).toArray(new String[0]));
        return arr;
    }

    public String getParameter(String name) { return getParameterMap().getFirst(name); }
    public String[] getParameterValues(String name) {
        List<String> values = getParameterMap().get(name);
        return values == null ? null : values.toArray(new String[0]);
    }

    protected abstract MultiValueMap<String, String> parseParameters();

    public Charset getCharacterEncoding() { return characterEncoding; }
    public void setCharacterEncoding(Charset characterEncoding) { this.characterEncoding = characterEncoding; }
    public Map<String, Object> getAttributes() { return attributes; }
    public Object getAttribute(String name) { return attributes.get(name); }
    public void setAttribute(String name, Object o) { attributes.put(name, o); }
    public Object removeAttribute(String name) { return attributes.remove(name); }
    public <T> T getAttribute(RequestAttribute<T> key) { return (T) fastAttributes[key.getIndex()]; }
    public <T> void setAttribute(RequestAttribute<T> key, T value) { fastAttributes[key.getIndex()] = value; }
    public WebContext getWebContext() { return webContext; }
    public RequestContext getRequestContext() { return this; }
    public void complete() { this.lifecycleCompleted = true; }
    public boolean isCompleted() { return lifecycleCompleted; }
    public int getFilterIndexAndIncrement() { return filterIndex++; }

    @Override public Principal getPrincipal() { return null; }

    @Override
    public ServerHttpAsyncRequestControl getAsyncRequestControl(ServerHttpResponse response) {
        return AsyncSupportUtils.getAsyncWebRequest(this, (WebServerHttpResponse) response);
    }

    public List<Locale> getLocales() {
        if (locales == null) {
            String header = getHeaders().getFirst("Accept-Language");
            if (header == null || header.isEmpty()) {
                locales = Arrays.asList(Locale.getDefault());
            } else {
                locales = AcceptLanguageLocaleCache.get(header);
                if (locales == null) {
                    locales = parseAcceptLanguage(header);
                    AcceptLanguageLocaleCache.put(header, locales);
                }
            }
        }
        return locales;
    }

    public Locale getLocale() { return getLocales().get(0); }

    protected List<Locale> parseAcceptLanguage(String header) {
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(header);
            List<Locale> locales = new ArrayList<>(ranges.size());
            for (Locale.LanguageRange range : ranges) {
                String tag = range.getRange();
                if ("*".equals(tag)) continue;
                Locale locale = Locale.forLanguageTag(tag);
                if (!locale.getLanguage().isEmpty()) locales.add(locale);
            }
            if (locales.isEmpty()) return Arrays.asList(Locale.getDefault());
            return locales;
        } catch (IllegalArgumentException ex) {
            return Arrays.asList(Locale.getDefault());
        }
    }

    public static class AcceptLanguageLocaleCache {
        private static final int MAX_SIZE = 256;
        private static final Map<String, List<Locale>> CACHE =
                Collections.synchronizedMap(new LinkedHashMap<String, List<Locale>>(16, 0.75f, true) {
                    protected boolean removeEldestEntry(Map.Entry<String, List<Locale>> eldest) { return size() > MAX_SIZE; }
                });
        public static List<Locale> get(String header) { return CACHE.get(header); }
        public static void put(String header, List<Locale> locales) { CACHE.put(header, locales); }
    }
}