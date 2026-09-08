package io.springperf.web.context;

import io.springperf.web.core.DispatcherHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Collections;

import static io.springperf.web.context.PropertiesConstant.CONTEXT_PATH;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebContextTest {

    private DispatcherHandler handler;
    private ApplicationProperties props;
    private ApplicationContext ctx;
    private WebContext webContext;

    @BeforeEach
    void setUp() throws Exception {
        handler = mock(DispatcherHandler.class);
        when(handler.getComponentName()).thenReturn("DispatcherHandler");
        props = mock(ApplicationProperties.class);
        when(props.get(CONTEXT_PATH, "/")).thenReturn("");
        ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(any())).thenReturn(Collections.emptyMap());

        webContext = new WebContext(handler, props);
        webContext.setApplicationContext(ctx);
    }

    @Test
    void constructor_registersDispatcherHandler() {
        assertNotNull(webContext.webComponents.get("DispatcherHandler"));
    }

    @Test
    void constructor_setsContextPath() {
        assertEquals("", webContext.getContextPath());
    }

    @Test
    void constructor_setsProps() {
        assertSame(props, webContext.getProps());
    }

    @Test
    void startLifecycle_triggersFullLifecycle() {
        webContext.startLifecycle();
        // Lifecycle should have run through phase3
        assertNotNull(webContext.getWebContext());
    }

    @Test
    void startLifecycle_initsDispatcherHandler() {
        webContext.startLifecycle();
        // DispatcherHandler's initWithWebContext should have been called during init
        verify(handler, atLeast(1)).initWithWebContext(webContext);
    }

    @Test
    void destroy_triggersDestroy() throws Exception {
        webContext.startLifecycle();

        LifecycleWebComponent extra = mock(LifecycleWebComponent.class);
        when(extra.getComponentName()).thenReturn("extra");
        webContext.registerWebComponent(extra);

        webContext.destroy();
        verify(extra).destroyComponent();
    }

    @Test
    void getBeanFromCtx_noBeans_returnsNull() {
        webContext.initWithWebContext(webContext); // set the self-reference for getWebContext()
        Object result = webContext.getBeanFromCtx(String.class);
        assertNull(result);
    }

    @Test
    void getBeanFromCtx_singleBean_returnsIt() {
        webContext.initWithWebContext(webContext);
        String bean = "testBean";
        java.util.Map<String, String> singletonMap = new java.util.HashMap<>();
        singletonMap.put("bean1", bean);
        when(ctx.getBeansOfType(String.class)).thenReturn(singletonMap);
        assertSame(bean, webContext.getBeanFromCtx(String.class));
    }

    @Test
    void getBeanFromCtx_multipleBeans_returnsHighestPriority() {
        webContext.initWithWebContext(webContext);
        // 使用类级 @Order 的独立类型：排序确定性来自注解，不依赖 HashMap 迭代序
        OrderedType high = new HighPriorityBean();
        OrderedType low = new LowPriorityBean();
        java.util.Map<String, OrderedType> multiMap = new java.util.HashMap<>();
        multiMap.put("lowBean", low);
        multiMap.put("highBean", high);
        when(ctx.getBeansOfType(OrderedType.class)).thenReturn(multiMap);
        OrderedType result = webContext.getBeanFromCtx(OrderedType.class);
        assertTrue(result instanceof HighPriorityBean,
                "AnnotationAwareOrderComparator 应返回 @Order 最小（优先级最高）的 Bean");
    }

    private interface OrderedType {
    }

    @org.springframework.core.annotation.Order(1)
    private static class HighPriorityBean implements OrderedType {
    }

    @org.springframework.core.annotation.Order(100)
    private static class LowPriorityBean implements OrderedType {
    }

    @Test
    void getOrder_returnsMaxValue() {
        assertEquals(Integer.MAX_VALUE, webContext.getOrder());
    }

    @Test
    void contextPath_fromProps_usesFormattedPath() {
        ApplicationProperties customProps = mock(ApplicationProperties.class);
        when(customProps.get(CONTEXT_PATH, "/")).thenReturn("/api");
        WebContext ctx = new WebContext(mock(DispatcherHandler.class), customProps);
        assertEquals("/api", ctx.getContextPath());
    }

    @Test
    void lifecycle_doubleInitIsIdempotent() {
        webContext.startLifecycle();
        webContext.startLifecycle();
        // Should not throw - second call is no-op due to AtomicBoolean guard
    }

    @Test
    void startLifecycle_failure_cleansUpAndAllowsRetry() throws Exception {
        // 注册一个 Phase1 抛异常的组件：第一次启动失败
        LifecycleWebComponent failing = mock(LifecycleWebComponent.class);
        when(failing.getComponentName()).thenReturn("failing");
        doThrow(new IllegalStateException("phase1 boom"))
                .doNothing() // 第二次调用不再抛异常，验证可重试
                .when(failing).initComponentPhase1();
        webContext.registerWebComponent(failing);

        assertThrows(RuntimeException.class, () -> webContext.startLifecycle());

        // 失败后应清理已初始化组件，并复位 lifecycleStarted
        verify(failing).destroyComponent();

        // 第二次启动（该组件不再抛异常）应成功
        assertDoesNotThrow(() -> webContext.startLifecycle());
        verify(failing, times(2)).initComponentPhase1();
    }

    @Test
    void startLifecycle_failure_cleansUpOtherComponents() throws Exception {
        // 一个正常组件 + 一个失败组件：失败清理应波及已初始化的正常组件
        LifecycleWebComponent normal = mock(LifecycleWebComponent.class);
        when(normal.getComponentName()).thenReturn("normal");
        webContext.registerWebComponent(normal);

        LifecycleWebComponent failing = mock(LifecycleWebComponent.class);
        when(failing.getComponentName()).thenReturn("failing");
        doThrow(new IllegalStateException("phase2 boom")).when(failing).initComponentPhase2();
        webContext.registerWebComponent(failing);

        assertThrows(RuntimeException.class, () -> webContext.startLifecycle());

        verify(normal).destroyComponent();
        verify(failing).destroyComponent();
    }

    @Test
    void destroy_thenStartLifecycle_restartsCleanly() throws Exception {
        // F：destroy 后应支持重新 start 完整生命周期
        webContext.startLifecycle();
        assertTrue(webContext.getWebContext() != null);

        webContext.destroy();

        // destroy 已复位 lifecycleStarted，再次 start 应重新初始化
        assertDoesNotThrow(() -> webContext.startLifecycle());
        verify(handler, atLeast(2)).initWithWebContext(webContext);
    }

    @Test
    void destroy_beforeStart_doesNotThrow() throws Exception {
        // 从未 startLifecycle 直接 destroy：State=NEW 时不应清理组件，也不应抛异常
        LifecycleWebComponent comp = mock(LifecycleWebComponent.class);
        when(comp.getComponentName()).thenReturn("unstarted");
        webContext.registerWebComponent(comp);

        assertDoesNotThrow(() -> webContext.destroy());
        verify(comp, never()).destroyComponent();
    }

    @Test
    void destroyComponent_clearsStaticMetadataCaches() throws Exception {
        // 2-7 回归：destroy 必须清空进程级静态元数据缓存，防 devtools/新 ClassLoader 重启时
        // 旧 ClassLoader 被钉住（metaspace 泄漏）。缓存为纯缓存，清空后自动重建。
        webContext.startLifecycle();
        java.lang.reflect.Method method = Object.class.getMethod("toString");
        Object bean = new Object();
        io.springperf.web.core.mapping.MappingCacheKey<String> key =
                io.springperf.web.core.mapping.MappingCacheKey.createMethodCacheKey(String.class);
        io.springperf.web.core.mapping.MappingHandlerMethod mhm =
                new io.springperf.web.core.mapping.MappingHandlerMethod(bean, method);
        mhm.set(key, "v");
        assertEquals("v", mhm.get(key));

        webContext.destroy();

        io.springperf.web.core.mapping.MappingHandlerMethod fresh =
                new io.springperf.web.core.mapping.MappingHandlerMethod(bean, method);
        assertNull(fresh.get(key), "destroy 后静态缓存应被清空");
    }
}