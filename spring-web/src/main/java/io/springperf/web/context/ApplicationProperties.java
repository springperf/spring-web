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
 * String/boolean 仍使用 {@link Map}{@code <String, String>} 缓存。
 */
@Slf4j
public class ApplicationProperties implements EnvironmentAware {

    private PropertyResolver properties;

    /** long/int 数值缓存，value 直接存原始 long 类型 */
    private final Object2LongOpenHashMap longCache = new Object2LongOpenHashMap();
    /** 字符串/布尔值缓存 */
    private final Map<String, String> stringCache = new HashMap<>();

    /**
     * 获取 long 属性。默认值在 {@link PropertiesConstant} 中静态定义，调用方无需传入。
     * 双检锁懒加载：先无锁检查缓存，未命中则同步后二次检查并写入。
     */
    public long getLong(String key) {
        if (!longCache.containsKey(key)) {
            synchronized (longCache) {
                if (!longCache.containsKey(key)) {
                    String value = properties.getProperty(key);
                    long longVal = value != null ? Long.parseLong(value)
                                                 : PropertiesConstant.getDefault(key);
                    longCache.put(key, longVal);
                    return longVal;
                }
            }
        }
        return longCache.get(key);
    }

    /**
     * 获取 int 属性。内部复用 longCache，取回后 cast 为 int。
     */
    public int getInt(String key) {
        return (int) getLong(key);
    }

    /**
     * 获取 String 属性。
     * 双检锁懒加载：先无锁检查缓存，未命中则同步后二次检查并写入。
     */
    public String get(String key, String defaultValue) {
        String value = stringCache.get(key);
        if (value == null) {
            synchronized (stringCache) {
                value = stringCache.get(key);
                if (value == null) {
                    value = properties.getProperty(key, defaultValue);
                    stringCache.put(key, value);
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
