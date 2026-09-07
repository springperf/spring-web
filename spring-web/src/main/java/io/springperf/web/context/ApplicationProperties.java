package io.springperf.web.context;

import io.springperf.web.util.Object2LongOpenHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic property accessor. Provides only typed getter methods;
 * property keys are defined in {@link PropertiesConstant}.
 * <p>
 * long/int 使用 {@link Object2LongOpenHashMap} 直接缓存原始类型，消除每次调用的装箱和解析开销。
 * String/boolean 使用 {@link Map}{@code <String, String>} 缓存。
 * <p>
 * 两个缓存均为 <b>volatile copy-on-write 发布</b>：读完全无锁（读到的是已发布、之后永不
 * 改写的完整快照）；写仅在缓存未命中时发生（配置键有界），锁内拷贝一份 → 写入 → volatile
 * 发布。消除了双检锁实现中「无锁读与锁内 put（rehash 分步替换字段）」的竞态。
 */
@Slf4j
public class ApplicationProperties implements EnvironmentAware {

    private PropertyResolver properties;

    /** long/int 数值缓存，value 直接存原始 long 类型；volatile 快照，copy-on-write 发布 */
    private volatile Object2LongOpenHashMap longCache = new Object2LongOpenHashMap(64);
    /** 字符串/布尔值缓存；volatile 快照，copy-on-write 发布 */
    private volatile Map<String, String> stringCache = new HashMap<>();

    /**
     * 获取 long 属性。默认值在 {@link PropertiesConstant} 中静态定义，调用方无需传入。
     * <p>copy-on-write 发布：读完全无锁（volatile 快照，已发布后永不改写）；
     * 写仅在缓存未命中时发生（配置键有界），锁内拷贝一份新表 → put → volatile 发布。
     * 消除了原双检锁实现中「无锁读与锁内 put（rehash 分步替换字段）」的竞态。</p>
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
     * 获取 String 属性。
     * copy-on-write 发布（与 {@link #getLong(String)} 相同的并发模型）：
     * null 值不缓存（与旧行为一致：缓存 null 会被当作未命中而每次重查）。
     */
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
        return Double.parseDouble(get(key, String.valueOf(defaultValue)));
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.properties = environment;
    }
}
