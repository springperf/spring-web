package io.springperf.web.core.pool;

import io.springperf.web.annotation.RunInPool;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.metrics.NoOpWebMetrics;
import io.springperf.web.core.metrics.WebMetrics;
import io.springperf.web.http.WebServerHttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 补充 BizPoolRegistry 覆盖率：Phase3 bean 发现、register 边界/替换、
 * 默认池异常配置、Spring 容器兜底解析、determinePool(MappingResult)、setDefaultPool、destroyComponent。
 */
@ExtendWith(MockitoExtension.class)
class BizPoolRegistryDetailsTest {

    private final BizPoolRegistry registry = new BizPoolRegistry();

    @Mock WebContext webContext;
    @Mock ApplicationContext applicationContext;
    @Mock WebServerHttpRequest request;

    @AfterEach
    void cleanup() throws Exception {
        registry.shutdownPools(1, TimeUnit.SECONDS);
        Field field = BizPoolRegistry.class.getDeclaredField("pools");
        field.setAccessible(true);
        Map<String, ExecutorService> pools = (Map<String, ExecutorService>) field.get(registry);
        pools.clear();
    }

    private static ThreadPoolExecutor newPool() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    }

    /* ==================== initComponentPhase3 ==================== */

    @Test
    void initComponentPhase3_registersExecutorBeans() throws Exception {
        setWebContext(registry, webContext, "default");
        when(webContext.getCtx()).thenReturn(applicationContext);
        when(webContext.getProps()).thenReturn(mock(io.springperf.web.context.ApplicationProperties.class));
        ExecutorService beanPool = mock(ExecutorService.class);
        Map<String, ExecutorService> beans = new LinkedHashMap<>();
        beans.put("beanPool", beanPool);
        when(applicationContext.getBeansOfType(ExecutorService.class)).thenReturn(beans);

        registry.initComponentPhase3();

        assertSame(beanPool, registry.getPool("beanPool"));
    }

    @Test
    void initComponentPhase3_doesNotOverrideExistingSameNamePool() throws Exception {
        setWebContext(registry, webContext, "default");
        ThreadPoolExecutor localPool = newPool();
        registry.register("beanPool", localPool);
        when(webContext.getCtx()).thenReturn(applicationContext);
        when(webContext.getProps()).thenReturn(mock(io.springperf.web.context.ApplicationProperties.class));
        ExecutorService beanPool = mock(ExecutorService.class);
        when(applicationContext.getBeansOfType(ExecutorService.class))
                .thenReturn(Collections.singletonMap("beanPool", beanPool));

        registry.initComponentPhase3();

        assertSame(localPool, registry.getPool("beanPool"), "本地同名池优先，不应被 bean 覆盖");
    }

    @Test
    void initComponentPhase3_emptyPoolsWarnsWhenCheckOnStartup()
            throws Exception {
        setWebContext(registry, webContext, "default");
        when(webContext.getCtx()).thenReturn(applicationContext);
        io.springperf.web.context.ApplicationProperties props =
                mock(io.springperf.web.context.ApplicationProperties.class);
        when(webContext.getProps()).thenReturn(props);
        when(props.getBoolean(eq(PropertiesConstant.CHECK_ON_STARTUP), eq(true))).thenReturn(true);
        when(applicationContext.getBeansOfType(ExecutorService.class))
                .thenReturn(Collections.emptyMap());

        registry.initComponentPhase3();

        assertTrue(registry.getPoolNames().isEmpty());
    }

    /* ==================== register 边界与替换 ==================== */

    @Test
    void register_nullNameOrExecutor_isNoOp() {
        registry.register(null, mock(ExecutorService.class));
        registry.register("name", null);
        assertTrue(registry.getPoolNames().isEmpty());
    }

    @Test
    void register_replaceExistingPool_shutsDownOld() throws InterruptedException {
        ExecutorService oldPool = mock(ExecutorService.class);
        when(oldPool.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        registry.register("p", oldPool);
        ExecutorService newPool = mock(ExecutorService.class);

        registry.register("p", newPool);

        verify(oldPool).shutdown();
        assertSame(newPool, registry.getPool("p"));
    }

    @Test
    void registerExecutor_delegatesToRegister() {
        ExecutorService executor = newPool();
        registry.registerExecutor("named", executor);
        assertSame(executor, registry.getPool("named"));
    }

    @Test
    void getPool_returnsNullForMissingName() {
        registry.register("p", newPool());
        assertNotNull(registry.getPool("p"));
        assertNull(registry.getPool("missing"));
    }

    /* ==================== 默认池异常配置 ==================== */

    @Test
    void initDefaultPoolFromConfig_negativeCoreOrMax_skipsCreation() {
        ExecutorService pool = initDefaultPoolWith(webContext, -1, 200, 0);
        assertNull(pool, "core<0 时不创建默认池");
    }

    @Test
    void initDefaultPoolFromConfig_zeroQueueCapacity_forcedToOne() {
        ExecutorService pool = initDefaultPoolWith(webContext, 1, 1, 0);
        assertNotNull(pool);
        assertTrue(pool instanceof ThreadPoolExecutor);
        assertEquals(1, ((ThreadPoolExecutor) pool).getQueue().remainingCapacity(),
                "queueCapacity<=0 时应调整为 1");
    }

    private static ExecutorService initDefaultPoolWith(WebContext wc, int core, int max, int queue) {
        io.springperf.web.context.ApplicationProperties props =
                mock(io.springperf.web.context.ApplicationProperties.class);
        when(wc.getProps()).thenReturn(props);
        when(wc.getWebComponentWithDefault(eq(WebMetrics.class), any())).thenReturn(NoOpWebMetrics.INSTANCE);
        when(props.getBoolean(eq("spring.threads.virtual.enabled"), eq(false))).thenReturn(false);
        when(props.getInt(PropertiesConstant.POOL_CORE_POOL_SIZE)).thenReturn(core);
        when(props.getInt(PropertiesConstant.POOL_MAX_POOL_SIZE)).thenReturn(max);
        when(props.getInt(PropertiesConstant.POOL_KEEP_ALIVE_TIME)).thenReturn(60);
        when(props.getInt(PropertiesConstant.POOL_QUEUE_CAPACITY)).thenReturn(queue);
        when(props.get(PropertiesConstant.POOL_DEFAULT_EXECUTE_MODE,
                PropertiesConstant.POOL_DEFAULT_EXECUTE_MODE_DEFAULT)).thenReturn("default");
        BizPoolRegistry fresh = new BizPoolRegistry();
        fresh.initWithWebContext(wc);
        try {
            return fresh.getDefaultPool();
        } finally {
            fresh.shutdownPools(1, TimeUnit.SECONDS);
        }
    }

    /* ==================== resolvePool：Spring 容器兜底 ==================== */

    @Test
    void determinePool_withAnnotationPoolFoundInSpring_autoRegisters() {
        setWebContext(registry, webContext, "default");
        ExecutorService beanExecutor = mock(ExecutorService.class);
        when(webContext.getCtx()).thenReturn(applicationContext);
        when(applicationContext.getBean("servicePool", ExecutorService.class)).thenReturn(beanExecutor);

        MappingHandlerMethod mhm = mappingHandler(ServicePoolCtrl.class, "doIt");

        assertSame(beanExecutor, registry.determinePool(mhm));
        assertSame(beanExecutor, registry.getPool("servicePool"), "Spring 兜底成功后应自动注册到本地 pools");
    }

    @Test
    void determinePool_withAnnotationAndBeanLookupFails_throwsIllegalState() {
        setWebContext(registry, webContext, "default");
        when(webContext.getCtx()).thenReturn(applicationContext);
        when(applicationContext.getBean("missingPool", ExecutorService.class))
                .thenThrow(new RuntimeException("bean not found"));

        MappingHandlerMethod mhm = mappingHandler(MissingPoolCtrl.class, "doIt");

        assertThrows(IllegalStateException.class, () -> registry.determinePool(mhm));
    }

    static class ServicePoolCtrl {
        @RunInPool("servicePool")
        public void doIt() {}
    }

    static class MissingPoolCtrl {
        @RunInPool("missingPool")
        public void doIt() {}
    }

    /* ==================== determinePool(MappingResult) ==================== */

    @Test
    void determinePool_matchedResult_resolvesPool() throws Exception {
        registry.register("default", newPool());
        setDefaultExecuteMode(registry, "default");
        PathMappingContext ctx = pathMappingContext(NoAnnoCtrl.class, "doIt");
        MappingResult matched = MappingResult.matched(ctx);

        assertNotNull(registry.determinePool(request, matched));
    }

    @Test
    void determinePool_notMatchedResult_returnsNull() {
        MappingResult notFound = MappingResult.notFound();
        assertNull(registry.determinePool(request, notFound));
    }

    static class NoAnnoCtrl {
        public void doIt() {}
    }

    /* ==================== setDefaultPool ==================== */

    @Test
    void setDefaultPool_withExplicitAnnotation_doesNotOverride() {
        registry.register("default", newPool());
        registry.register("other", newPool());
        MappingHandlerMethod mhm = mappingHandler(DefaultPoolCtrl.class, "doIt");

        registry.setDefaultPool(mhm, registry.getPool("other"));

        ExecutorService resolved = registry.determinePool(mhm);
        assertSame(registry.getPool("default"), resolved, "显式 @RunInPool 优先于默认池");
    }

    @Test
    void setDefaultPool_withNullExecutor_cachesNoPool() {
        MappingHandlerMethod mhm = mappingHandler(NoAnnoCtrl.class, "doIt");
        registry.setDefaultPool(mhm, null);
        assertNull(registry.determinePool(mhm));
    }

    @Test
    void setDefaultPool_withExecutor_cachesPool() {
        registry.register("default", newPool());
        MappingHandlerMethod mhm = mappingHandler(NoAnnoCtrl.class, "doIt");
        ExecutorService pool = registry.getPool("default");
        registry.setDefaultPool(mhm, pool);
        assertSame(pool, registry.determinePool(mhm));
    }

    @Test
    void setDefaultPool_withEventloopAnnotation_doesNotCachePool() {
        registry.register("default", newPool());
        MappingHandlerMethod mhm = mappingHandler(EventLoopCtrl.class, "doIt");
        registry.setDefaultPool(mhm, registry.getPool("default"));
        assertNull(registry.determinePool(mhm), "注解优先，默认池不应生效");
    }

    static class DefaultPoolCtrl {
        @RunInPool("default")
        public void doIt() {}
    }

    static class EventLoopCtrl {
        @RunInPool(RunInPool.EVENTLOOP)
        public void doIt() {}
    }

    /* ==================== destroyComponent ==================== */

    @Test
    void destroyComponent_shutsDownAllPoolsAndClears() throws InterruptedException {
        ExecutorService pool = mock(ExecutorService.class);
        when(pool.awaitTermination(anyLong(), any(TimeUnit.class))).thenReturn(true);
        registry.register("p", pool);

        registry.destroyComponent();

        verify(pool).shutdown();
        assertTrue(registry.getPoolNames().isEmpty());
    }

    /* ==================== helpers ==================== */

    private static MappingHandlerMethod mappingHandler(Class<?> controllerClass, String methodName) {
        try {
            Object bean = controllerClass.getDeclaredConstructor().newInstance();
            return new MappingHandlerMethod(
                    new HandlerMethod(bean, controllerClass.getDeclaredMethod(methodName)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static PathMappingContext pathMappingContext(Class<?> controllerClass, String methodName)
            throws Exception {
        Object bean = controllerClass.getDeclaredConstructor().newInstance();
        HandlerMethod hm = new HandlerMethod(bean, controllerClass.getDeclaredMethod(methodName));
        return new PathMappingContext(hm, java.util.Collections.emptyList(), "/x");
    }

    private static void setWebContext(BizPoolRegistry registry, WebContext wc, String defaultMode) {
        try {
            Field field = io.springperf.web.context.BaseWebComponent.class.getDeclaredField("webContext");
            field.setAccessible(true);
            field.set(registry, wc);
            setDefaultExecuteMode(registry, defaultMode);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setDefaultExecuteMode(BizPoolRegistry registry, String defaultMode) {
        try {
            Field field = BizPoolRegistry.class.getDeclaredField("defaultExecuteMode");
            field.setAccessible(true);
            field.set(registry, defaultMode);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}