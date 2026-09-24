package io.springperf.web.context;

import java.util.HashMap;
import java.util.Map;

import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.lang.Nullable;

import io.springperf.web.util.Object2LongOpenHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Generic property accessor. Provides only typed getter methods; property keys are defined in
 * {@link PropertiesConstant}.
 * <p>
 * long/int 使用 {@link Object2LongOpenHashMap} 直接缓存原始类型，消除每次调用的装箱和解析开销。 String/boolean 使用
 * {@link Map}{@code <String, String>} 缓存。
 * <p>
 * 两个缓存均为 <b>volatile copy-on-write 发布</b>：读完全无锁（读到的是已发布、之后永不 改写的完整快照）；写仅在缓存未命中时发生（配置键有界），锁内拷贝一份 → 写入 → volatile
 * 发布。消除了双检锁实现中「无锁读与锁内 put（rehash 分步替换字段）」的竞态。
 */
@Slf4j
public class ApplicationProperties implements EnvironmentAware {

    private PropertyResolver properties;

    /** long/int 数值缓存，value 直接存原始 long 类型；volatile 快照，copy-on-write 发布 */
    private volatile Object2LongOpenHashMap longCache = new Object2LongOpenHashMap(64);
    /** 字符串/布尔值缓存；volatile 快照，copy-on-write 发布 */
    private volatile Map<String, String> stringCache = new HashMap<>();
    /** Duration 配置缓存（毫秒口径）；volatile 快照，copy-on-write 发布 */
    private volatile Object2LongOpenHashMap durationMillisCache = new Object2LongOpenHashMap(16);
    /** Duration 配置缓存（秒口径）；volatile 快照，copy-on-write 发布 */
    private volatile Object2LongOpenHashMap durationSecondsCache = new Object2LongOpenHashMap(16);

    /** Duration 缓存哨兵：键未配置（value 为 null/空白），返回时回退调用方默认值。 */
    private static final long UNCONFIGURED = Long.MIN_VALUE;

    // ---- 热路径配置快照：每请求读取的键定义为类型化字段，请求路径字段直读（零 hash） ----
    // 字段在构造期自 Environment 急切解析（fail-fast：解析失败 = 启动失败，对齐 Boot
    // @ConfigurationProperties 的行为），此后 getter 为纯字段读——无哨兵、无双检锁。
    // clearCache()（Spring Cloud 配置刷新）重读赋值：逐字段 catch，解析失败保留旧值，
    // 不让脏推送中断刷新监听器。字段为实例级——主/管理端口两个 WebContext 各持各的快照。

    /** {@code server.max-parameter-count} 快照（每请求参数值总数上限，≤0 不限制）。 */
    private volatile int maxParameterCount = (int) PropertiesConstant
            .getDefault(PropertiesConstant.SERVER_MAX_PARAMETER_COUNT);
    /** {@code server.http.timeout}（毫秒）快照。 */
    private volatile long httpTimeoutMillis = PropertiesConstant.HTTP_TIMEOUT_DEFAULT;
    /** {@code server.http.max-in-memory-size} 快照（请求体内联阈值，超过则走大体内存/磁盘路径）。 */
    private volatile int maxInMemorySize = (int) PropertiesConstant
            .getDefault(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE);

    /** 供 EnvironmentAware 形态使用：构造后经 {@link #setEnvironment(Environment)} 注入并急切解析。 */
    public ApplicationProperties() {
    }

    /** 构造期注入 Environment 并急切解析热路径字段（解析失败向上抛 → 启动失败）。 */
    public ApplicationProperties(Environment environment) {
        setEnvironment(environment);
    }

    /**
     * {@code server.max-parameter-count}（每请求参数值总数上限，≤0 不限制）。 纯字段读（构造期已急切解析，clearCache 后随刷新更新）。
     */
    public int getMaxParameterCount() {
        return maxParameterCount;
    }

    /**
     * {@code server.http.timeout}（毫秒，响应超时）。纯字段读。
     */
    public long getHttpTimeoutMillis() {
        return httpTimeoutMillis;
    }

    /**
     * {@code server.http.max-in-memory-size}（请求体内联阈值）。纯字段读。
     * <p>
     * 原为 {@code NettyServerHttpRequest.cachedLargeBodyLimit} 静态哨兵缓存 + {@code clearStaticCache()} 手工刷新接线；迁移至此与另两个热键同构：
     * 构造期急切解析、clearCache 自动重解析、实例级隔离（主/管理端口各持快照）。
     * </p>
     */
    public int getMaxInMemorySize() {
        return maxInMemorySize;
    }

    /**
     * 重读热路径字段。failFast=true（构造期）：解析失败向上抛（启动失败）； failFast=false（运行期刷新）：单字段解析失败仅告警并保留旧值，隔离配置中心脏推送。
     */
    private void refreshHotFields(boolean failFast) {
        try {
            maxParameterCount = getInt(PropertiesConstant.SERVER_MAX_PARAMETER_COUNT);
        } catch (Exception e) {
            if (failFast) {
                throw e;
            }
            log.warn("Failed to re-resolve {}: keeping previous value {}",
                    PropertiesConstant.SERVER_MAX_PARAMETER_COUNT, maxParameterCount, e);
        }
        try {
            httpTimeoutMillis = getDurationMillis(PropertiesConstant.HTTP_TIMEOUT,
                    PropertiesConstant.HTTP_TIMEOUT_DEFAULT);
        } catch (Exception e) {
            if (failFast) {
                throw e;
            }
            log.warn("Failed to re-resolve {}: keeping previous value {}", PropertiesConstant.HTTP_TIMEOUT,
                    httpTimeoutMillis, e);
        }
        try {
            maxInMemorySize = getInt(PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE);
        } catch (Exception e) {
            if (failFast) {
                throw e;
            }
            log.warn("Failed to re-resolve {}: keeping previous value {}", PropertiesConstant.HTTP_MAX_IN_MEMORY_SIZE,
                    maxInMemorySize, e);
        }
    }

    /**
     * 获取 long 属性。默认值在 {@link PropertiesConstant} 中静态定义，调用方无需传入。
     * <p>
     * copy-on-write 发布：读完全无锁（volatile 快照，已发布后永不改写）； 写仅在缓存未命中时发生（配置键有界），锁内拷贝一份新表 → put → volatile 发布。 消除了原双检锁实现中「无锁读与锁内
     * put（rehash 分步替换字段）」的竞态。
     * </p>
     */
    public long getLong(String key) {
        Object2LongOpenHashMap cache = longCache;
        if (!cache.containsKey(key)) {
            synchronized (this) {
                cache = longCache;
                if (!cache.containsKey(key)) {
                    Object2LongOpenHashMap next = new Object2LongOpenHashMap(cache);
                    String value = properties.getProperty(key);
                    next.put(key, value != null ? Long.parseLong(value) : PropertiesConstant.getDefault(key));
                    longCache = next;
                    return next.get(key);
                }
            }
        }
        return cache.get(key);
    }

    /**
     * 获取 int 属性。内部复用 longCache，取回后 cast 为 int。
     */
    public int getInt(String key) {
        return (int) getLong(key);
    }

    /**
     * 获取 String 属性。 copy-on-write 发布（与 {@link #getLong(String)} 相同的并发模型）： null 值不缓存（与旧行为一致：缓存 null 会被当作未命中而每次重查）。
     */
    /** defaultValue 为 null 时，键缺失即返回 null（委托 Properties#getProperty 的语义）。 */
    @Nullable
    public String get(String key, String defaultValue) {
        Map<String, String> cache = stringCache;
        String value = cache.get(key);
        if (value == null) {
            synchronized (this) {
                cache = stringCache;
                value = cache.get(key);
                if (value == null) {
                    value = properties.getProperty(key, defaultValue);
                    if (value != null) {
                        Map<String, String> next = new HashMap<>(cache);
                        next.put(key, value);
                        stringCache = next;
                    }
                }
            }
        }
        return value;
    }

    /**
     * 获取 boolean 属性。
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    /**
     * 获取 double 属性。
     */
    public double getDouble(String key, double defaultValue) {
        String fallback = String.valueOf(defaultValue);
        // get(key, ...) 声明为 @Nullable：此处默认值非空，兜底分支实际不可达，仅为契约完整
        String value = get(key, fallback);
        return Double.parseDouble(value != null ? value : fallback);
    }

    /**
     * 以毫秒为单位读取 Duration 风格配置，对齐 Spring Boot 的 Duration 解析：
     * <ul>
     * <li>纯数字 → 视为毫秒</li>
     * <li>后缀 {@code s/m/h/d} → 秒/分/时/天</li>
     * <li>{@code PT..S} 形式 → 交由 {@link java.time.Duration#parse}</li>
     * </ul>
     * 未配置时回退 {@code defaultMillis}。
     * <p>
     * <b>结果缓存</b>：解析结果（含「未配置」哨兵）写入 durationMillisCache，命中后零解析开销—— 该方法在每请求的 {@code setTimeout()} 热路径上调用，字符串解析不可接受。缓存模型与
     * {@link #getLong(String)} 相同的 volatile copy-on-write 发布；使用独立表而非复用 longCache， 因 getLong 对未配置键缓存
     * {@link PropertiesConstant} 默认值，与本方法的「未配置哨兵」语义冲突。
     * </p>
     */
    public long getDurationMillis(String key, long defaultMillis) {
        Object2LongOpenHashMap cache = durationMillisCache;
        if (cache.containsKey(key)) {
            return resolveCached(cache.get(key), defaultMillis);
        }
        synchronized (this) {
            cache = durationMillisCache;
            if (cache.containsKey(key)) {
                return resolveCached(cache.get(key), defaultMillis);
            }
            String v = properties.getProperty(key);
            long resolved = (v == null || v.trim().isEmpty()) ? UNCONFIGURED : parseDurationMillis(v.trim());
            Object2LongOpenHashMap next = new Object2LongOpenHashMap(cache);
            next.put(key, resolved);
            durationMillisCache = next;
            return resolveCached(resolved, defaultMillis);
        }
    }

    /**
     * 以秒为单位读取 Duration 风格配置，语义与 {@link #getDurationMillis(String, long)} 一致， 仅单位不同（供
     * {@code server.servlet.session.timeout} 等秒语义键统一使用）： 裸数字视为秒、支持 {@code 30s/1m/1h/1d} 后缀与 ISO-8601 {@code PT..S}。
     * 未配置时回退 {@code defaultSeconds}。结果缓存模型同 {@link #getDurationMillis(String, long)}（秒口径独立表）。
     */
    public long getDurationSeconds(String key, long defaultSeconds) {
        Object2LongOpenHashMap cache = durationSecondsCache;
        if (cache.containsKey(key)) {
            return resolveCached(cache.get(key), defaultSeconds);
        }
        synchronized (this) {
            cache = durationSecondsCache;
            if (cache.containsKey(key)) {
                return resolveCached(cache.get(key), defaultSeconds);
            }
            String v = properties.getProperty(key);
            long resolved = (v == null || v.trim().isEmpty()) ? UNCONFIGURED : parseDurationSeconds(v.trim());
            Object2LongOpenHashMap next = new Object2LongOpenHashMap(cache);
            next.put(key, resolved);
            durationSecondsCache = next;
            return resolveCached(resolved, defaultSeconds);
        }
    }

    /** 缓存命中值 → 调用方结果：哨兵表示未配置，回退调用方默认值。 */
    private static long resolveCached(long cached, long defaultValue) {
        return cached == UNCONFIGURED ? defaultValue : cached;
    }

    /**
     * 统一的 Duration 解析（毫秒口径）：
     * <ul>
     * <li>裸数字 → 毫秒</li>
     * <li>后缀 {@code s/m/h/d} → 秒/分/时/天</li>
     * <li>{@code PT..S} → ISO-8601 {@link java.time.Duration#parse}</li>
     * </ul>
     */
    private static long parseDurationMillis(String v) {
        char last = v.charAt(v.length() - 1);
        if (Character.isDigit(last)) {
            return Long.parseLong(v);
        }
        if (v.startsWith("PT") || v.startsWith("pt")) {
            return java.time.Duration.parse(v).toMillis();
        }
        // 多字符后缀必须先于单字符分支匹配，否则 "500ms" 会被 's' 分支吞掉后缀去 parseLong("500m")
        String lower = v.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith("ms")) {
            return Long.parseLong(lower.substring(0, lower.length() - 2).trim());
        }
        if (lower.endsWith("us")) {
            return Long.parseLong(lower.substring(0, lower.length() - 2).trim()) / 1000L;
        }
        if (lower.endsWith("ns")) {
            return Long.parseLong(lower.substring(0, lower.length() - 2).trim()) / 1_000_000L;
        }
        long multiplier;
        switch (Character.toLowerCase(last)) {
            case 's':
                multiplier = 1000L;
                break;
            case 'm':
                multiplier = 60_000L;
                break;
            case 'h':
                multiplier = 3_600_000L;
                break;
            case 'd':
                multiplier = 86_400_000L;
                break;
            default:
                throw new IllegalArgumentException("Unknown duration unit: " + last);
        }
        return Long.parseLong(v.substring(0, v.length() - 1).trim()) * multiplier;
    }

    /**
     * 统一的 Duration 解析（秒口径）：裸数字按「秒」解析（对齐 Boot {@code server.servlet.session.timeout} 语义，不能复用
     * parseDurationMillis——其裸数字为毫秒）， 后缀与 ISO-8601 形式同毫秒口径。
     */
    private static long parseDurationSeconds(String v) {
        char last = v.charAt(v.length() - 1);
        if (Character.isDigit(last)) {
            return Long.parseLong(v);
        }
        return parseDurationMillis(v) / 1000L;
    }

    /**
     * 清空全部配置缓存（用于 Spring Cloud 等配置中心的动态刷新）。
     * <p>
     * <b>清空重建</b>：直接把两份缓存置为新的空表并 volatile 发布。读线程要么看到 旧的完整快照，要么看到新的空表（下次读从 {@link Environment} 重新加载）， 不存在「部分失效」的中间态——与本类
     * copy-on-write 的发布模型一致。 读路径无需任何改动，仍为零锁。
     * </p>
     * <p>
     * 加锁仅为与 {@link #getLong}/{@link #get(String, String)} 的写入路径互斥， 避免刷新瞬间同一 key 的重复加载；刷新是低频操作，无性能影响。
     * </p>
     * <p>
     * 清空后配置值以 {@link Environment} 当前值为准；若某键在 Environment 中已被移除， 将回退到 {@link PropertiesConstant#getDefault(String)}
     * 或调用方传入的默认值。
     * </p>
     */
    public void clearCache() {
        synchronized (this) {
            this.longCache = new Object2LongOpenHashMap(64);
            this.stringCache = new HashMap<>();
            this.durationMillisCache = new Object2LongOpenHashMap(16);
            this.durationSecondsCache = new Object2LongOpenHashMap(16);
        }
        // 热路径字段快照基于新配置重读赋值（逐字段容错：脏推送保留旧值）
        refreshHotFields(false);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.properties = environment;
        this.environment = environment;
        // Environment 就绪（构造注入 / Spring 回调）即急切解析热路径字段：
        // 此后 getter 为纯字段读，请求路径零配置解析
        if (environment != null) {
            refreshHotFields(true);
        }
    }

    /** 原始 Environment 引用（用于枚举属性键，如 {@code server.servlet.context-parameters.*}）。 */
    private Environment environment;

    /**
     * 返回以 {@code prefix} 开头的所有属性键的完整名称（未配置或无 Environment 时返回空集合）。 用于 {@code server.servlet.context-parameters.*}
     * 这类“显式块”的通配读取。
     */
    public java.util.List<String> getPropertyNames(String prefix) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (prefix == null || !(environment instanceof org.springframework.core.env.ConfigurableEnvironment)) {
            return names;
        }
        org.springframework.core.env.ConfigurableEnvironment env = (org.springframework.core.env.ConfigurableEnvironment) environment;
        for (org.springframework.core.env.PropertySource<?> ps : env.getPropertySources()) {
            if (ps instanceof org.springframework.core.env.EnumerablePropertySource) {
                for (String name : ((org.springframework.core.env.EnumerablePropertySource<?>) ps).getPropertyNames()) {
                    if (name.startsWith(prefix) && !names.contains(name)) {
                        names.add(name);
                    }
                }
            }
        }
        return names;
    }
}
