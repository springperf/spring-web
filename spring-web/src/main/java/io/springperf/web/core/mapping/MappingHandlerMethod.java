package io.springperf.web.core.mapping;

import io.springperf.web.core.invoker.InvokableHandlerMethod;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MappingHandlerMethod extends InvokableHandlerMethod {
    private static final Map<Class<?>, Object[]> classCacheInstanceMap = new ConcurrentHashMap<>();
    private static final Map<Method, Object[]> methodCacheInstanceMap = new ConcurrentHashMap<>();
    protected final Method userMethod;
    protected final Class<?> userClass;
    private volatile Object[] methodCache;
    private volatile Object[] classCache;

    public MappingHandlerMethod(Object bean, Method method) {
        super(bean, method);
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        userClass = ClassUtils.getUserClass(targetClass);
        userMethod = AopUtils.getMostSpecificMethod(getBridgedMethod(), userClass);
    }

    public MappingHandlerMethod(HandlerMethod handlerMethod) {
        super(handlerMethod);
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(handlerMethod.getBean());
        userClass = ClassUtils.getUserClass(targetClass);
        userMethod = AopUtils.getMostSpecificMethod(getBridgedMethod(), userClass);
    }

    public <T> T get(MappingCacheKey<T> key) {
        Object[] cache = getCache(key);
        if (key.index >= cache.length) {
            return null;
        }
        return (T) cache[key.index];
    }

    public <T> void set(MappingCacheKey<T> key, T value) {
        int index = key.index;
        Object[] cache = getCache(key);
        if (index >= cache.length) {
            synchronized (this) {
                cache = getCache(key);
                if (index >= cache.length) {
                    cache = Arrays.copyOf(cache, index + 1);
                    setCache(key, cache);
                }
                // 扩容路径：值与数组一起在锁内发布，避免锁外写孤儿数组 / 读者看到
                // 新数组但 index 槽位尚未写入的中间态。
                cache[index] = value;
            }
        } else {
            cache[index] = value;
        }
    }

    protected Object[] getCache(MappingCacheKey key) {
        if (key.classCache) {
            if (classCache == null) {
                classCache = classCacheInstanceMap.computeIfAbsent(userClass, k -> new Object[key.index + 1]);
            }
            return classCache;
        } else {
            if (methodCache == null) {
                methodCache = methodCacheInstanceMap.computeIfAbsent(userMethod, k -> new Object[key.index + 1]);
            }
            return methodCache;
        }
    }

    protected void setCache(MappingCacheKey key, Object[] cache) {
        if (key.classCache) {
            classCache = cache;
            classCacheInstanceMap.put(userClass, cache);
        } else {
            methodCache = cache;
            methodCacheInstanceMap.put(userMethod, cache);
        }
    }

    public Class<?> getUserClass() {
        return userClass;
    }
}
