package io.springperf.web.core.pool;

import io.springperf.web.annotation.RunInPool;
import io.springperf.web.context.*;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.http.WebServerHttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;
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

    private static final boolean VIRTUAL_THREADS_AVAILABLE = detectVirtualThreads();

    private final Map<String, ExecutorService> pools = new ConcurrentHashMap<>();

    /** 预缓存的默认执行策略：null 表示未初始化（降级为 EventLoop），"eventloop" 表示 EventLoop，其他值为池名。 */
    private volatile String defaultExecuteMode;

    private boolean virtualThreadEnabled;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.virtualThreadEnabled = isVirtualThreadEnabled();
        initDefaultPoolFromConfig();
        // 缓存默认执行策略，避免请求路径上查询配置
        this.defaultExecuteMode = webContext.getProps().get(
                PropertiesConstant.POOL_DEFAULT_EXECUTE_MODE, PropertiesConstant.POOL_DEFAULT_EXECUTE_MODE_DEFAULT);
    }

    private boolean isVirtualThreadEnabled() {
        return webContext.getProps().getBoolean("spring.threads.virtual.enabled", false);
    }

    /**
     * 检测 JDK 21+ 是否可用。
     */
    private static boolean detectVirtualThreads() {
        try {
            Thread.class.getMethod("ofVirtual");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * 通过反射创建虚拟线程 {@link ThreadFactory}（兼容 JDK 17 编译）。
     * 在 JDK 21+ 上调用 {@code Thread.ofVirtual().name("perf-virtual-").factory()}。
     * 通过公开接口 {@code java.lang.Thread.Builder.OfVirtual} 反射，避免模块系统限制。
     */
    private static ThreadFactory createVirtualThreadFactory() {
        try {
            Object ofVirtual = Thread.class.getMethod("ofVirtual").invoke(null);
            // Thread.Builder.OfVirtual 是公开接口，方法可访问
            Class<?> ofVirtualIface = Class.forName("java.lang.Thread$Builder$OfVirtual");
            Method nameMethod = ofVirtualIface.getMethod("name", String.class);
            Object named = nameMethod.invoke(ofVirtual, "perf-virtual-");
            Method factoryMethod = ofVirtualIface.getMethod("factory");
            return (ThreadFactory) factoryMethod.invoke(named);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create virtual thread factory (JDK 21+ required)", e);
        }
    }

    /**
     * 从 ApplicationProperties 读取配置，创建 "default" 线程池。
     */
    private void initDefaultPoolFromConfig() {
        if (virtualThreadEnabled) {
            if (VIRTUAL_THREADS_AVAILABLE) {
                ThreadFactory factory = createVirtualThreadFactory();
                ExecutorService executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L,
                        TimeUnit.SECONDS, new SynchronousQueue<>(), factory);
                pools.put("default", executor);
                log.info("BizPool [default] created with virtual threads");
                return;
            } else {
                log.warn("spring.threads.virtual.enabled=true but JDK 21+ is not available, fallback to platform threads");
            }
        }
        int corePoolSize = webContext.getProps().getInt(PropertiesConstant.POOL_CORE_POOL_SIZE);
        int maxPoolSize = webContext.getProps().getInt(PropertiesConstant.POOL_MAX_POOL_SIZE);
        int keepAliveTime = webContext.getProps().getInt(PropertiesConstant.POOL_KEEP_ALIVE_TIME);
        int queueCapacity = webContext.getProps().getInt(PropertiesConstant.POOL_QUEUE_CAPACITY);

        if (corePoolSize < 0 || maxPoolSize < 0) {
            log.warn("pool.core-pool-size or pool.max-pool-size < 0, skip default pool creation");
            return;
        }

        if (queueCapacity <= 0) {
            queueCapacity = 1; // 至少为 1，避免 DirectHandoffQueue
            log.warn("pool.queue-capacity <= 0, adjusted to 1");
        }

        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                corePoolSize, maxPoolSize, keepAliveTime,
                TimeUnit.SECONDS, new LinkedBlockingQueue<>(queueCapacity));
        pools.put("default", executor);
        log.info("BizPool [default] created: core={}, max={}", corePoolSize, maxPoolSize);
    }

    /**
     * 注册一个命名线程池。
     *
     * @throws IllegalArgumentException 当名称为保留关键字 "eventloop" 时
     */
    public void register(String name, ThreadPoolExecutor executor) {
        if (name == null || executor == null) {
            return;
        }
        if (RunInPool.EVENTLOOP.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException(
                    "'" + RunInPool.EVENTLOOP + "' is a reserved keyword and cannot be used as a pool name");
        }
        pools.put(name, executor);
        log.info("BizPool [{}] registered: core={}, max={}", name, executor.getCorePoolSize(), executor.getMaximumPoolSize());
    }

    /**
     * 注册一个已创建的 {@link ExecutorService}。
     */
    public void registerExecutor(String name, ExecutorService executor) {
        if (name == null || executor == null) return;
        if (RunInPool.EVENTLOOP.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException(
                    "'" + RunInPool.EVENTLOOP + "' is a reserved keyword and cannot be used as a pool name");
        }
        pools.put(name, executor);
        log.info("BizPool [{}] registered as ExecutorService", name);
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
     * 解析优先级（高 → 低）：
     * <ol>
     *   <li>{@code @RunInPool(RunInPool.EVENTLOOP)} → EventLoop</li>
     *   <li>{@code @RunInPool("poolName")} → 对应线程池</li>
     *   <li>无注解 → 读取 {@code pool.default-execute-mode} 配置
     *       （默认 "default"，即 default 线程池；设 "eventloop" 可切回 EventLoop）</li>
     * </ol>
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
                mappingContext.getMethod(), RunInPool.class);
        if (annotation != null) {
            String poolName = annotation.value();
            if (RunInPool.EVENTLOOP.equalsIgnoreCase(poolName)) {
                mappingContext.set(BIZ_POOL_KEY, NO_POOL);
                return null;
            }
            return resolvePool(poolName, mappingContext);
        }

        // 无注解：使用全局默认策略（启动时已缓存到 this.defaultExecuteMode）
        String strategy = this.defaultExecuteMode;
        if (strategy == null || RunInPool.EVENTLOOP.equalsIgnoreCase(strategy)) {
            mappingContext.set(BIZ_POOL_KEY, NO_POOL);
            return null;
        }
        return resolvePool(strategy, mappingContext);
    }

    /**
     * 根据池名称解析并缓存 {@link ExecutorService}。
     * 名称 "eventloop" 不会到达此方法，调用前已由 {@link #determinePool(MappingHandlerMethod)} 拦截处理。
     */
    private ExecutorService resolvePool(String poolName, MappingHandlerMethod mappingContext) {
        ExecutorService executor = pools.get(poolName);
        if (executor == null) {
            throw new IllegalStateException(
                    "Pool '" + poolName + "' referenced on " + mappingContext.getMethod().toGenericString()
                            + " does not exist. Available pools: " + getPoolNames());
        }
        mappingContext.set(BIZ_POOL_KEY, executor);
        return executor;
    }

    /**
     * 根据 {@link MappingResult} 确定线程池。有映射时委托给 {@link #determinePool(MappingHandlerMethod)}，
     * 无映射时返回 null（直接在 EventLoop 中执行）。
     */
    public ExecutorService determinePool(WebServerHttpRequest req, MappingResult mappingResult) {
        if (mappingResult.isMatched()) {
            return determinePool(mappingResult.getMatchedContext());
        }
        return null;
    }

    /**
     * 为指定 handler 设置默认线程池，仅在用户未通过 {@link RunInPool} 显式指定时生效。
     * 用于 {@code @BatchMapping} 等需要修改默认线程模型但尊重用户显式配置的场景。
     * <p>
     * 传入 {@code null} 表示默认走 EventLoop（不入业务线程池）。
     *
     * @param mappingContext 目标 handler
     * @param executor       默认线程池，{@code null} 表示 EventLoop
     */
    public void setDefaultPool(MappingHandlerMethod mappingContext, ExecutorService executor) {
        RunInPool annotation = AnnotatedElementUtils.findMergedAnnotation(
                mappingContext.getMethod(), RunInPool.class);
        if (annotation == null) {
            mappingContext.set(BIZ_POOL_KEY, executor == null ? NO_POOL : executor);
        }
    }

    /**
     * 返回所有已注册线程池的名称。
     */
    public Set<String> getPoolNames() {
        return pools.keySet();
    }

    /**
     * 优雅关闭所有池：先 {@link ExecutorService#shutdown()} 再等待任务完成。
     *
     * @param timeout 最大等待时间
     * @param unit    时间单位
     */
    public void shutdownPools(long timeout, TimeUnit unit) {
        pools.values().forEach(pool -> {
            try {
                pool.shutdown();
                if (!pool.awaitTermination(timeout, unit)) {
                    log.warn("BizPool did not terminate within timeout, forcing shutdown");
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                log.warn("BizPool shutdown interrupted", e);
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
    }

    @Override
    public void destroyComponent() {
        shutdownPools(30, TimeUnit.SECONDS);
        pools.clear();
        log.info("BizPoolRegistry destroyed");
    }
}
