package io.springperf.web.core.mapping;

import io.springperf.web.core.invoker.InvokableHandlerMethod;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MappingHandlerMethod extends InvokableHandlerMethod {
    private static final Map<Class<?>, Object[]> classCacheInstanceMap = new ConcurrentHashMap<>();
    private static final Map<Method, Object[]> methodCacheInstanceMap = new ConcurrentHashMap<>();
    protected Class<?> userClass;
    private Object[] methodCache;
    private Object[] classCache;

    public MappingHandlerMethod(Object bean, Method method) {
        super(bean, method);
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        userClass = ClassUtils.getUserClass(targetClass);
    }

    public MappingHandlerMethod(HandlerMethod handlerMethod) {
        super(handlerMethod);
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(handlerMethod.getBean());
        userClass = ClassUtils.getUserClass(targetClass);
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
            }
        }
        cache[index] = value;
    }

    protected Object[] getCache(MappingCacheKey key) {
        if (key.classCache) {
            if (classCache == null) {
                classCache = classCacheInstanceMap.computeIfAbsent(userClass, k -> new Object[key.index + 1]);
            }
            return classCache;
        } else {
            if (methodCache == null) {
                methodCache = methodCacheInstanceMap.computeIfAbsent(getBridgedMethod(), k -> new Object[key.index + 1]);
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
            methodCacheInstanceMap.put(getBridgedMethod(), cache);
        }
    }

    public Class<?> getUserClass() {
        return userClass;
    }
}
