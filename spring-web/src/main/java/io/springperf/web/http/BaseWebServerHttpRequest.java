package io.springperf.web.http;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.http.server.ServerHttpAsyncRequestControl;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.MultiValueMap;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.async.AsyncSupportUtils;

public abstract class BaseWebServerHttpRequest implements WebServerHttpRequest, RequestContext {

    protected final WebContext webContext;
    private final String uriStrWithQuery;
    private final String path;

    /**
     * 查询串之前的部分（{@link #getUriStr()}）。<b>懒计算</b>：仅「发布请求完成事件」 （{@code spring.mvc.publish-request-handled-events}，默认
     * false）与访问日志等路径会读它， 默认配置下热路径完全不读 → 免掉每请求一次 {@code substring} 分配与一次 {@code '?'} 扫描。
     */
    private volatile String uriStr;

    /**
     * String 键属性（Servlet 语义 / 非类型化属性）。<b>懒分配</b>：热路径只用类型化属性数组 （{@link #fastAttributes}），该 map 仅在真正出现 String
     * 键读写、或类型化属性索引超出 数组长度（请求创建后又有新 {@code RequestAttribute} 注册）时才创建。 并发下由 {@link #attributes()} 双检锁保证只建一次（异步 dispatch
     * 可能跨线程访问）。
     */
    private volatile Map<String, Object> attributes;

    /**
     * 参数表。<b>懒初始化 + 双检锁</b>：与 {@link #attributes} 同款发布语义——写发生在 {@code synchronized (this)} 内、读完全无锁，因此字段必须是 volatile 才满足
     * JMM（否则 异步 dispatch 的另一线程可能看到"非 null 但未完全发布"的引用）。
     */
    private volatile MultiValueMap<String, String> parameterMap;
    private Charset characterEncoding = StandardCharsets.UTF_8;
    private List<Locale> locales;
    protected final Object[] fastAttributes = new Object[RequestAttribute.getMaxSize()];
    protected int filterIndex = 0;
    /**
     * HEAD 请求标志：由 HttpMethodMatcher 路由匹配时统一写入（RFC 7231 §4.3.2）。
     * <p>
     * volatile：写入方是路由匹配所在的线程（EventLoop），读取方是业务线程池中的处理器。二者之间只有 「提交任务到线程池」这一道 happens-before 兜底，一旦将来出现不经线程池交接的读取路径就会被打破；
     * boolean 的 volatile 读写在 x86 上开销可忽略，这里不做性能取舍。
     * </p>
     */
    protected volatile boolean headRequest;

    protected BaseWebServerHttpRequest(WebContext webContext, String uriStrWithQuery, String resolvedPath) {
        this.webContext = webContext;
        this.uriStrWithQuery = uriStrWithQuery;
        this.path = resolvedPath;
    }

    /** 标记当前请求为 HEAD（由路由层在匹配到支持 GET 的处理器时调用）。 */
    public void markAsHeadRequest() {
        this.headRequest = true;
    }

    @Override
    public boolean isHeadRequest() {
        return headRequest;
    }

    public String getUriStrWithQuery() {
        return uriStrWithQuery;
    }

    /**
     * 查询串之前的部分。首次访问才截取（见 {@link #uriStr} 字段说明）； 派生自不可变字段，故并发下重复计算也无害（幂等，结果恒等）。
     */
    public String getUriStr() {
        String value = uriStr;
        if (value == null) {
            int queryIndex = uriStrWithQuery.indexOf('?');
            value = queryIndex == -1 ? uriStrWithQuery : uriStrWithQuery.substring(0, queryIndex);
            uriStr = value;
        }
        return value;
    }

    public String getPath() {
        return path;
    }

    /** 懒创建 String 键属性表（双检锁：异步 dispatch 可能跨线程首次访问）。 */
    private Map<String, Object> attributes() {
        Map<String, Object> map = attributes;
        if (map == null) {
            synchronized (this) {
                if (attributes == null) {
                    attributes = new ConcurrentHashMap<>();
                }
                map = attributes;
            }
        }
        return map;
    }

    public MultiValueMap<String, String> getParameterMap() {
        if (parameterMap == null) {
            // 与 getBodyBytes() 相同的双检锁：异步 dispatch（另一线程）可能再次读取参数，
            // 无锁懒初始化会在并发时重复解析（multipart 场景重复建 decoder、读同一 ByteBuf）。
            synchronized (this) {
                if (parameterMap == null) {
                    parameterMap = parseParameters();
                }
            }
        }
        return parameterMap;
    }

    public Map<String, String[]> getParameterMapArray() {
        MultiValueMap<String, String> pm = getParameterMap();
        Map<String, String[]> arr = new HashMap<>();
        // 用 entrySet 而非 keySet + get：后者对每个键多做一次哈希查找（本方法是热路径）
        for (Map.Entry<String, List<String>> entry : pm.entrySet()) {
            arr.put(entry.getKey(), entry.getValue().toArray(new String[0]));
        }
        return arr;
    }

    public String getParameter(String name) {
        return getParameterMap().getFirst(name);
    }

    public String[] getParameterValues(String name) {
        List<String> values = getParameterMap().get(name);
        return values == null ? null : values.toArray(new String[0]);
    }

    protected abstract MultiValueMap<String, String> parseParameters();

    public Charset getCharacterEncoding() {
        return characterEncoding;
    }

    public void setCharacterEncoding(Charset characterEncoding) {
        this.characterEncoding = characterEncoding;
    }

    public Map<String, Object> getAttributes() {
        return attributes();
    }

    public Object getAttribute(String name) {
        Map<String, Object> map = attributes;
        return map == null ? null : map.get(name);
    }

    /**
     * Servlet 规范（Jakarta Servlet §4.3/§3.10）：setAttribute(name, null) 等价于 removeAttribute(name)。 attributes 为
     * ConcurrentHashMap（拒绝 null 值），null 值直接 put 会抛 NPE—— 典型触发点：PerfRequestDispatcher.forward 在无查询串时设置
     * FORWARD_QUERY_STRING=null， 导致所有 forward / JSP 视图渲染请求 500。
     */
    public void setAttribute(String name, Object o) {
        if (o == null) {
            Map<String, Object> map = attributes;
            if (map != null) {
                map.remove(name);
            }
        } else {
            attributes().put(name, o);
        }
    }

    public Object removeAttribute(String name) {
        Map<String, Object> map = attributes;
        return map == null ? null : map.remove(name);
    }

    private static final String FAST_ATTR_PREFIX = BaseWebServerHttpRequest.class.getName() + ".FAST_ATTR.";

    public <T> T getAttribute(RequestAttribute<T> key) {
        int idx = key.getIndex();
        if (idx < fastAttributes.length) {
            return (T) fastAttributes[idx];
        }
        Map<String, Object> map = attributes;
        return map == null ? null : (T) map.get(FAST_ATTR_PREFIX + idx);
    }

    public <T> void setAttribute(RequestAttribute<T> key, T value) {
        int idx = key.getIndex();
        if (idx < fastAttributes.length) {
            fastAttributes[idx] = value;
        } else {
            attributes().put(FAST_ATTR_PREFIX + idx, value);
        }
    }

    public WebContext getWebContext() {
        return webContext;
    }

    public RequestContext getRequestContext() {
        return this;
    }

    public int getFilterIndexAndIncrement() {
        return filterIndex++;
    }

    @Override
    public Principal getPrincipal() {
        return null;
    }

    @Override
    public ServerHttpAsyncRequestControl getAsyncRequestControl(ServerHttpResponse response) {
        return AsyncSupportUtils.getAsyncWebRequest(this, (WebServerHttpResponse) response);
    }

    public List<Locale> getLocales() {
        if (locales == null) {
            String header = getHeaders().getFirst("Accept-Language");
            if (header == null || header.isEmpty()) {
                locales = defaultLocaleList();
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

    public Locale getLocale() {
        return getLocales().get(0);
    }

    /**
     * JVM 默认 Locale 的单元素列表。随 {@code Locale.setDefault()} 变化自动重建，稳态零分配 （原实现每次请求都
     * {@code Arrays.asList(Locale.getDefault())} 新建列表对象）。
     */
    static List<Locale> defaultLocaleList() {
        Locale current = Locale.getDefault();
        List<Locale> cached = DEFAULT_LOCALE_LIST;
        if (cached == null || cached.get(0) != current) {
            cached = Collections.singletonList(current);
            DEFAULT_LOCALE_LIST = cached;
        }
        return cached;
    }

    private static volatile List<Locale> DEFAULT_LOCALE_LIST;

    protected List<Locale> parseAcceptLanguage(String header) {
        try {
            List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(header);
            List<Locale> locales = new ArrayList<>(ranges.size());
            for (Locale.LanguageRange range : ranges) {
                String tag = range.getRange();
                if ("*".equals(tag))
                    continue;
                Locale locale = Locale.forLanguageTag(tag);
                if (!locale.getLanguage().isEmpty())
                    locales.add(locale);
            }
            if (locales.isEmpty())
                return defaultLocaleList();
            return locales;
        } catch (IllegalArgumentException ex) {
            return defaultLocaleList();
        }
    }

    /**
     * Accept-Language 解析结果缓存（按头字符串）。
     * <p>
     * 原实现用 {@code Collections.synchronizedMap(LRU)}——每个带该头的请求都要抢**全局锁**， 高并发下是扩展性瓶颈（CPU 采样看不见，表现为线程阻塞）。解析结果小且幂等， 改为
     * {@link ConcurrentHashMap} 无锁读；超过上限整体清空（近似淘汰）， 只影响命中率、不影响正确性。
     * </p>
     */
    public static class AcceptLanguageLocaleCache {
        private static final int MAX_SIZE = 256;
        private static final ConcurrentMap<String, List<Locale>> CACHE = new ConcurrentHashMap<>();

        public static List<Locale> get(String header) {
            return CACHE.get(header);
        }

        public static void put(String header, List<Locale> locales) {
            if (CACHE.size() >= MAX_SIZE) {
                CACHE.clear();
            }
            CACHE.put(header, locales);
        }
    }
}
