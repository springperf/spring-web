package io.springperf.web.core.mapping;

import io.springperf.web.core.invoker.InvokableHandlerMethod;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.support.AopUtils;
import org.springframework.util.ClassUtils;
import org.springframework.web.method.HandlerMethod;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MappingHandlerMethod extends InvokableHandlerMethod {
    private static final Map<Class<?>, Object[]> classCacheInstanceMap = new ConcurrentHashMap<>();
    private static final Map<Method, Object[]> methodCacheInstanceMap = new ConcurrentHashMap<>();
    /**
     * 存活实例的弱引用集合：使 {@link #clearCache} 能直接把各实例的本地缓存字段置空，
     * 而非只能清空静态表数组槽位（否则扩容后悬挂在旧数组上的实例无法被失效）。用弱引用避免
     * 钉住实例导致的内存泄漏；迭代时顺带清理已回收的引用。
     */
    private static final Set<WeakReference<MappingHandlerMethod>> INSTANCES =
            ConcurrentHashMap.newKeySet();
    protected final Method userMethod;
    protected final Class<?> userClass;
    private volatile Object[] methodCache;
    private volatile Object[] classCache;

    public MappingHandlerMethod(Object bean, Method method) {
        super(bean, method);
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(bean);
        userClass = ClassUtils.getUserClass(targetClass);
        userMethod = AopUtils.getMostSpecificMethod(getBridgedMethod(), userClass);
        INSTANCES.add(new WeakReference<>(this));
    }

    public MappingHandlerMethod(HandlerMethod handlerMethod) {
        super(handlerMethod);
        Class<?> targetClass = AopProxyUtils.ultimateTargetClass(handlerMethod.getBean());
        userClass = ClassUtils.getUserClass(targetClass);
        userMethod = AopUtils.getMostSpecificMethod(getBridgedMethod(), userClass);
        INSTANCES.add(new WeakReference<>(this));
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
            // 实例字段快路径：已填充后不再触碰静态 map，避免每次读 map 的额外开销（缓存才有意义）。
            // 扩容（set 时 copyOf 生成新数组并写回静态表）后，旧数组引用会"悬"在其它实例上，
            // 因此 clearCache 会在清除静态表数组槽位的同时，直接把各存活实例的本地字段置空，
            // 下次访问即重新从静态表拉取已失效的新数组（见 clearCache）。
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

    /**
     * 清空指定 {@link MappingCacheKey} 在全部类/方法上的缓存槽。
     * <p>供模块在运行期使缓存的每方法元数据失效时调用（如新增解析器/转换器后，旧缓存
     * 仍指向旧的解析结果）。直接遍历静态缓存的所有 {@code Object[]} 按 index 置空——
     * 覆盖 controller 映射与 {@code @ExceptionHandler} 生成的 {@code MappingHandlerMethod}，
     * 且不触发数组扩容、不新增 map 条目。置空原子，读者看到 null 即按原逻辑重新解析回填。</p>
     * <p>注意：该方法只清槽位值，<b>不删除 map 条目</b>，因此不释放 Class/Method 键；
     * 需要释放键（防 ClassLoader 泄漏）请用 {@link #clearAllCaches()}。</p>
     */
    public static <T> void clearCache(MappingCacheKey<T> key) {
        int index = key.index;
        // 1) 清空静态表数组槽位：覆盖所有 userClass/userMethod；新创建的实例 computeIfAbsent 取到的是
        //    已失效数组，自然返回 null。
        for (Object[] arr : classCacheInstanceMap.values()) {
            if (index < arr.length) {
                arr[index] = null;
            }
        }
        for (Object[] arr : methodCacheInstanceMap.values()) {
            if (index < arr.length) {
                arr[index] = null;
            }
        }
        // 2) 直接置空所有存活实例的本地缓存字段：扩容后某些实例仍持有已脱离静态表的旧数组引用，
        //    仅清静态表槽位无法使它们失效；置空本地字段后，下次访问经 getCache 的快路径判断为 null，
        //    重新从静态表拉取已失效的新数组，确保 Javadoc 声称的"运行期失效"真正生效。
        for (java.util.Iterator<WeakReference<MappingHandlerMethod>> it = INSTANCES.iterator(); it.hasNext(); ) {
            MappingHandlerMethod m = it.next().get();
            if (m == null) {
                it.remove();
                continue;
            }
            if (key.classCache) {
                m.classCache = null;
            } else {
                m.methodCache = null;
            }
        }
    }

    /**
     * 清空全部类/方法级元数据缓存（整表 {@code clear()}）。
     * <p>由 {@code WebContext.destroyComponent()} 在上下文销毁时调用，释放被静态缓存钉住的
     * Class/Method/ClassLoader（devtools 等新 ClassLoader 重启场景防 metaspace 泄漏）。
     * 缓存为纯缓存，清空后下次访问自动重建。</p>
     */
    public static void clearAllCaches() {
        classCacheInstanceMap.clear();
        methodCacheInstanceMap.clear();
    }

    public Class<?> getUserClass() {
        return userClass;
    }
}
