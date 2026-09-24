package io.springperf.web.core.pool;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;

import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;

import io.springperf.web.annotation.RunInPool;
import io.springperf.web.context.*;
import io.springperf.web.core.mapping.MappingCacheKey;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.metrics.NoOpWebMetrics;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.http.WebServerHttpRequest;
import lombok.extern.slf4j.Slf4j;

/**
 * 业务线程池注册表，自身作为 {@link LifecycleWebComponent} 初始化。
 * <p>
 * Phase1 从 {@link ApplicationProperties} 读取配置并创建线程池， 开发人员也可在 Phase1 后通过 {@link #register(String, ThreadPoolExecutor)}
 * 添加自定义池。 池映射通过 {@link MappingCacheKey} 延迟解析：首次调用时才扫描 {@link RunInPool} 并缓存，后续零反射。
 * <p>
 * 配置方式（application.properties）：
 *
 * <pre>
 * pool.core-pool-size=50
 * pool.max-pool-size=200
 * pool.keep-alive-time=60
 * </pre>
 */
@Slf4j
public class BizPoolRegistry extends BaseWebComponent {

    private static final MappingCacheKey<Object> BIZ_POOL_KEY = MappingCacheKey.createMethodCacheKey(Object.class);
    private static final Object NO_POOL = new Object();

    private final Map<String, ExecutorService> pools = new ConcurrentHashMap<>();

    /** 预缓存的默认执行策略：null 表示未初始化（降级为 EventLoop），"eventloop" 表示 EventLoop，其他值为池名。 */
    private volatile String defaultExecuteMode;

    /** {@link #defaultExecuteMode} 是否为 EventLoop 的预计算布尔（请求路径只读，避免字符串比较）。 */
    private volatile boolean defaultEventLoop;

    private WebMetrics metrics;
    private boolean virtualThreadEnabled;

    @Override
    public void initWithWebContext(WebContext webContext) {
        super.initWithWebContext(webContext);
        this.metrics = webContext.getWebComponentWithDefault(WebMetrics.class, NoOpWebMetrics.INSTANCE);
        this.virtualThreadEnabled = isVirtualThreadEnabled();
        initDefaultPoolFromConfig();
        // 缓存默认执行策略，避免请求路径上查询配置
        String mode = webContext.getProps().get(PropertiesConstant.POOL_DEFAULT_EXECUTE_MODE,
                PropertiesConstant.POOL_DEFAULT_EXECUTE_MODE_DEFAULT);
        this.defaultExecuteMode = mode;
        // 与 determinePool 的判定保持一致：null / "eventloop" 均表示无池（EventLoop 同步执行）
        this.defaultEventLoop = mode == null || RunInPool.EVENTLOOP.equalsIgnoreCase(mode);
    }

    /**
     * 默认执行策略是否为 EventLoop（{@code pool.default-execute-mode=eventloop} 或未配置）。
     * <p>
     * 供响应超时装配决策使用：EventLoop 同步执行期间，超时定时器与被执行的处理器同线程
     * （{@code ctx.executor().schedule(...)}），<b>不可能在处理器执行期间触发</b>——凡是能让它执行的 时刻，要么响应已提交（被 setCommitted
     * 取消）、要么请求已交棒（由对应位置补装配/异步重装配）。 故该模式下请求开始无需装配响应超时，纯属每请求的调度开销。
     * </p>
     */
    public boolean isDefaultEventLoop() {
        return defaultEventLoop;
    }

    /**
     * 默认业务池是否**真的**使用虚拟线程：{@code spring.threads.virtual.enabled=true} <b>且</b> 运行在 JDK
     * 21+（{@link VirtualThreadSupport#isAvailable()}）。
     * <p>
     * 供 batch 等模块判定线程模型：为 {@code true} 时 {@code default} 池以虚拟线程执行任务 （池的上限 / 队列 / 拒绝策略等 {@code pool.*}
     * 语义不变，仅线程类型为虚拟线程）；为 {@code false}（含属性开启但 JDK &lt; 21 的回落情形）时是常规平台线程池。
     * </p>
     */
    public boolean usesVirtualThreads() {
        return virtualThreadEnabled && VirtualThreadSupport.isAvailable();
    }

    private boolean isVirtualThreadEnabled() {
        return webContext.getProps().getBoolean("spring.threads.virtual.enabled", false);
    }

    @Override
    public void initComponentPhase3() throws Exception {
        // 自动发现 Spring 容器中 ExecutorService Bean，注册到池
        ApplicationContext ctx = webContext.getCtx();
        Map<String, ExecutorService> executorBeans = ctx.getBeansOfType(ExecutorService.class);
        for (Map.Entry<String, ExecutorService> entry : executorBeans.entrySet()) {
            String beanName = entry.getKey();
            // 不覆盖已注册的同名池（如配置创建的 "default" 池优先）
            if (!pools.containsKey(beanName)) {
                register(beanName, entry.getValue());
            }
        }

        // check-on-startup：pools 仍为空则告警
        if (webContext.getProps().getBoolean(PropertiesConstant.CHECK_ON_STARTUP, true) && pools.isEmpty()) {
            log.warn("No thread pools registered — consider configuring pool.* properties "
                    + "or declaring ExecutorService beans");
        }
    }

    /**
     * 从 ApplicationProperties 读取配置，创建 "default" 线程池。
     * <p>
     * <b>虚拟线程模式（JDK 21+ 且 {@code spring.threads.virtual.enabled=true}）只替换线程工厂</b>： {@code pool.core-pool-size} /
     * {@code pool.max-pool-size} / {@code pool.queue-capacity} / {@code pool.keep-alive-time}
     * 的上限与拒绝语义完全不变——用户显式配置的并发旋钮在任何模式下 都生效，虚拟线程只解决"阻塞占用平台线程"的问题（与 batch 模块 bizExecutor 的处理方式一致）。
     * </p>
     */
    private void initDefaultPoolFromConfig() {
        int corePoolSize = webContext.getProps().getInt(PropertiesConstant.POOL_CORE_POOL_SIZE);
        int maxPoolSize = webContext.getProps().getInt(PropertiesConstant.POOL_MAX_POOL_SIZE);
        int keepAliveTime = webContext.getProps().getInt(PropertiesConstant.POOL_KEEP_ALIVE_TIME);
        int queueCapacity = webContext.getProps().getInt(PropertiesConstant.POOL_QUEUE_CAPACITY);

        // 虚拟线程模式：只换线程工厂，池结构（上限 / 队列 / 拒绝）与平台模式完全一致
        ThreadFactory threadFactory = null;
        if (virtualThreadEnabled) {
            if (VirtualThreadSupport.isAvailable()) {
                threadFactory = VirtualThreadSupport.newThreadFactory("perf-virtual-");
            } else {
                log.warn(
                        "spring.threads.virtual.enabled=true but JDK 21+ is not available, fallback to platform threads");
            }
        }

        if (corePoolSize < 0 || maxPoolSize < 0) {
            log.warn("pool.core-pool-size or pool.max-pool-size < 0, skip default pool creation");
            return;
        }

        if (queueCapacity <= 0) {
            queueCapacity = 1; // 至少为 1，避免 DirectHandoffQueue
            log.warn("pool.queue-capacity <= 0, adjusted to 1");
        }

        ThreadPoolExecutor executor = threadFactory == null
                ? new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAliveTime, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(queueCapacity))
                : new ThreadPoolExecutor(corePoolSize, maxPoolSize, keepAliveTime, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(queueCapacity), threadFactory);
        pools.put("default", executor);
        log.info("BizPool [default] created: core={}, max={}, queue={}, threads={}", corePoolSize, maxPoolSize,
                queueCapacity, threadFactory == null ? "platform" : "virtual");
    }

    /**
     * 注册一个命名线程池。
     *
     * @throws IllegalArgumentException
     *             当名称为保留关键字 "eventloop" 时
     */
    public void register(String name, ExecutorService executor) {
        if (name == null || executor == null) {
            return;
        }
        if (RunInPool.EVENTLOOP.equalsIgnoreCase(name)) {
            throw new IllegalArgumentException(
                    "'" + RunInPool.EVENTLOOP + "' is a reserved keyword and cannot be used as a pool name");
        }
        ExecutorService old = pools.put(name, executor);
        if (old != null && old != executor) {
            log.warn("BizPool [{}] replaced. Shutting down old pool: {}", name, old);
            shutdownPool(old);
        }
        if (metrics != null && executor instanceof ThreadPoolExecutor) {
            metrics.registerPoolGauges(name, (ThreadPoolExecutor) executor);
        }
        log.info("BizPool [{}] registered: executor={}", name, executor.getClass().getSimpleName());
    }

    /**
     * 注册一个已创建的 {@link ExecutorService}。
     */
    public void registerExecutor(String name, ExecutorService executor) {
        register(name, executor);
    }

    /**
     * 关闭单个线程池，等待任务完成。
     */
    private static void shutdownPool(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 获取指定名称的线程池。
     */
    public ExecutorService getPool(String name) {
        return pools.get(name);
    }

    public ExecutorService getDefaultPool() {
        return pools.get("default");
    }

    /**
     * 延迟解析并缓存：先查 {@link MappingCacheKey}，未命中时解析 {@link RunInPool} 注解， 校验池名称存在后写入缓存。仅首次调用有注解反射开销。
     * <p>
     * 解析优先级（高 → 低）：
     * <ol>
     * <li>{@code @RunInPool(RunInPool.EVENTLOOP)} → EventLoop</li>
     * <li>{@code @RunInPool("poolName")} → 对应线程池</li>
     * <li>无注解 → 读取 {@code pool.default-execute-mode} 配置 （默认 "default"，即 default 线程池；设 "eventloop" 可切回 EventLoop）</li>
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
        RunInPool annotation = AnnotatedElementUtils.findMergedAnnotation(mappingContext.getMethod(), RunInPool.class);
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
     * 根据池名称解析并缓存 {@link ExecutorService}。 本地 pools 未命中时，兜底到 Spring 容器按 bean 名称查找并自动注册。 名称 "eventloop" 不会到达此方法，调用前已由
     * {@link #determinePool(MappingHandlerMethod)} 拦截处理。
     */
    private ExecutorService resolvePool(String poolName, MappingHandlerMethod mappingContext) {
        ExecutorService executor = pools.get(poolName);
        if (executor == null) {
            // 兜底：从 Spring 容器按 bean 名称查找
            if (webContext != null && webContext.getCtx() != null) {
                try {
                    executor = webContext.getCtx().getBean(poolName, ExecutorService.class);
                    register(poolName, executor);
                } catch (Exception ignored) {
                    log.debug("bean {} not found or error", poolName, ignored);
                }
            }
        }
        if (executor == null) {
            throw new IllegalStateException(
                    "Pool '" + poolName + "' referenced on " + mappingContext.getMethod().toGenericString()
                            + " does not exist. Available pools: " + getPoolNames());
        }
        mappingContext.set(BIZ_POOL_KEY, executor);
        return executor;
    }

    /**
     * 根据 {@link MappingResult} 确定线程池。有映射时委托给 {@link #determinePool(MappingHandlerMethod)}， 无映射时返回 null（直接在 EventLoop
     * 中执行）。
     */
    public ExecutorService determinePool(WebServerHttpRequest req, MappingResult mappingResult) {
        if (mappingResult.isMatched()) {
            return determinePool(mappingResult.getMatchedContext());
        }
        return null;
    }

    /**
     * 为指定 handler 设置默认线程池，仅在用户未通过 {@link RunInPool} 显式指定时生效。 用于 {@code @BatchMapping} 等需要修改默认线程模型但尊重用户显式配置的场景。
     * <p>
     * 传入 {@code null} 表示默认走 EventLoop（不入业务线程池）。
     *
     * @param mappingContext
     *            目标 handler
     * @param executor
     *            默认线程池，{@code null} 表示 EventLoop
     */
    public void setDefaultPool(MappingHandlerMethod mappingContext, ExecutorService executor) {
        RunInPool annotation = AnnotatedElementUtils.findMergedAnnotation(mappingContext.getMethod(), RunInPool.class);
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
     * @param timeout
     *            最大等待时间
     * @param unit
     *            时间单位
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
