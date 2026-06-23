package io.springperf.web.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;

import java.util.HashMap;
import java.util.Map;

/**
 * Generic property accessor. Provides only typed getter methods;
 * property keys are defined in {@link PropertiesConstant}.
 */
@Slf4j
public class ApplicationProperties implements EnvironmentAware {

    private PropertyResolver properties;

    private final Map<String, String> propertyCache = new HashMap<>();

    /**
     * 双检锁懒加载：先无锁检查缓存，未命中则同步后二次检查并写入。
     * 遵循项目既有 DCL 惯例（如 InterceptorRegistry、ExceptionHandlerAdvice）。
     */
    private String resolveProperty(String key, String defaultValue) {
        String value = propertyCache.get(key);
        if (value == null) {
            synchronized (propertyCache) {
                value = propertyCache.get(key);
                if (value == null) {
                    value = properties.getProperty(key, defaultValue);
                    propertyCache.put(key, value);
                }
            }
        }
        return value;
    }

    public String get(String key, String defaultValue) {
        return resolveProperty(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        return Integer.parseInt(resolveProperty(key, String.valueOf(defaultValue)));
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(resolveProperty(key, String.valueOf(defaultValue)));
    }

    public long getLong(String key, long defaultValue) {
        return Long.parseLong(resolveProperty(key, String.valueOf(defaultValue)));
    }

    public double getDouble(String key, double defaultValue) {
        return Double.parseDouble(resolveProperty(key, String.valueOf(defaultValue)));
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.properties = environment;
    }
}
