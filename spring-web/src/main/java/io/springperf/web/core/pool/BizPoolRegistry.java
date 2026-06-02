package io.springperf.web.core.pool;

import io.springperf.web.annotation.RunInPool;
import io.springperf.web.context.BaseWebComponent;
import io.springperf.web.context.LifecycleWebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

/**
 * 业务线程池注册表，自身作为 {@link LifecycleWebComponent} 初始化。
 * <p>
 * Phase1 从 {@link ApplicationProperties} 读取配置并创建线程池，
 * 开发人员也可在 Phase1 后通过 {@link #register(String, ThreadPoolExecutor)} 添加自定义池。
 * 池映射通过 {@link MappingCacheKey} 延迟解析：首次调用时才扫描 {@link RunInPool} 并缓存，后续零反射。
 * <p>
 * 配置方式（application.properties）：
 * <pre>
 * pool.core-pool-size=50
 * pool.max-pool-size=200
 * pool.keep-alive-time=60
 * </pre>
 */
@Slf4j
public class BizPoolRegistry extends BaseWebComponent {

    private static final MappingCacheKey<Object> BIZ_POOL_KEY =
            MappingCacheKey.createMethodCacheKey(Object.class);
    private static final Object NO_POOL = new Object();

    private final Map<String, ThreadPoolExecutor> pools = new ConcurrentHashMap<>();

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        initDefaultPoolFromConfig();
    }

    /**
     * 从 ApplicationProperties 读取配置，创建 "default" 线程池。
     */
    private void initDefaultPoolFromConfig() {
        int corePoolSize = webContext.getProps().getInt("pool.core-pool-size", 50);
        int maxPoolSize = webContext.getProps().getInt("pool.max-pool-size", 200);
        int keepAliveTime = webContext.getProps().getInt("pool.keep-alive-time", 60);

        if (corePoolSize < 0 || maxPoolSize < 0) {
            log.warn("pool.core-pool-size or pool.max-pool-size < 0, skip default pool creation");
            return;
        }

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize, maxPoolSize, keepAliveTime,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        register("default", executor);
    }

    /**
     * 注册一个命名线程池。
     */
    public void register(String name, ThreadPoolExecutor executor) {
        if (name == null || executor == null) {
            return;
        }
        pools.put(name, executor);
        log.info("BizPool [{}] registered: core={}, max={}", name, executor.getCorePoolSize(), executor.getMaximumPoolSize());
    }

    /**
     * 获取指定名称的线程池。
     */
    public ExecutorService getPool(String name) {
        return pools.get(name);
    }

    public ExecutorService getDefaultPool(){
        return pools.get("default");
    }

    /**
     * 延迟解析并缓存：先查 {@link MappingCacheKey}，未命中时解析 {@link RunInPool} 注解，
     * 校验池名称存在后写入缓存。仅首次调用有注解反射开销。
     * <p>
     * 未标注 {@link RunInPool} 的方法同样缓存 {@link #NO_POOL} 标记，后续请求无需反复反射。
     * 返回 null 表示直接在 EventLoop 中执行。
     */
    public ExecutorService determinePool(MappingHandlerMethod mappingContext) {
        Object cached = mappingContext.get(BIZ_POOL_KEY);
        if (cached == NO_POOL) {
            return null;
        }
        if (cached != null) {
            return (ExecutorService) cached;
        }
        // 缓存未命中：解析 @RunInPool
        RunInPool annotation = AnnotatedElementUtils.findMergedAnnotation(
                mappingContext.getBridgedMethod(), RunInPool.class);
        if (annotation == null) {
            mappingContext.set(BIZ_POOL_KEY, NO_POOL);  // 缓存 null 结果
            return null;
        }
        String poolName = annotation.value();
        ExecutorService executor = pools.get(poolName);
        if (executor == null) {
            throw new IllegalStateException(
                    "@RunInPool(\"" + poolName + "\") on " + mappingContext.getBridgedMethod().toGenericString()
                            + " references non-existent pool. Available pools: " + getPoolNames());
        }
        mappingContext.set(BIZ_POOL_KEY, executor);
        return executor;
    }

    /**
     * 返回所有已注册线程池的名称。
     */
    public Set<String> getPoolNames() {
        return pools.keySet();
    }

    @Override
    public void destroyComponent() {
        pools.values().forEach(pool -> {
            try {
                pool.shutdown();
            } catch (Exception ignored) {
                // shutdown 异常无需额外处理
            }
        });
        pools.clear();
        log.info("BizPoolRegistry destroyed");
    }
}
