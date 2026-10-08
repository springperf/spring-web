package io.springperf.web.context;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import static io.springperf.web.context.PropertiesConstant.CONTEXT_PATH;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;

import io.springperf.web.core.DispatcherHandler;

class WebComponentContainerTest {

    private WebContext createWebContext() {
        DispatcherHandler handler = mock(DispatcherHandler.class);
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(props.get(CONTEXT_PATH, "/")).thenReturn("/");
        WebContext wc = new WebContext(handler, props);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeansOfType(any())).thenReturn(Collections.emptyMap());
        wc.setCtx(ctx);
        return wc;
    }

    @Test
    void initWithWebContext_transitionsToInitContext() {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();

        container.initWithWebContext(webContext);
        assertNotNull(container.getWebContext());
    }

    @Test
    void initWithWebContext_twice_secondCallIsNoOp() {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();

        container.initWithWebContext(webContext);
        WebContext webContext2 = createWebContext();
        container.initWithWebContext(webContext2);
        assertSame(webContext, container.getWebContext());
    }

    @Test
    void registerWebComponent_addsToComponents() {
        WebComponentContainer container = new WebComponentContainer();
        WebComponent component = mock(WebComponent.class);
        when(component.getComponentName()).thenReturn("testComp");
        container.registerWebComponent(component);
        assertEquals(component, container.webComponents.get("testComp"));
    }

    @Test
    void registerWebComponent_duplicateName_overwritesByOrder() {
        WebComponentContainer container = new WebComponentContainer();
        WebComponent highOrder = mock(WebComponent.class);
        when(highOrder.getComponentName()).thenReturn("same");
        when(highOrder.getOrder()).thenReturn(10);

        WebComponent lowOrder = mock(WebComponent.class);
        when(lowOrder.getComponentName()).thenReturn("same");
        when(lowOrder.getOrder()).thenReturn(20);

        container.registerWebComponent(lowOrder);
        container.registerWebComponent(highOrder);

        // Higher priority (lower number) wins
        assertSame(highOrder, container.webComponents.get("same"));
    }

    @Test
    void registerWebComponent_duplicateName_destroysDeprecatedComponent() throws Exception {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        LifecycleWebComponent highOrder = mock(LifecycleWebComponent.class);
        when(highOrder.getComponentName()).thenReturn("same");
        when(highOrder.getOrder()).thenReturn(10);

        LifecycleWebComponent lowOrder = mock(LifecycleWebComponent.class);
        when(lowOrder.getComponentName()).thenReturn("same");
        when(lowOrder.getOrder()).thenReturn(20);

        container.registerWebComponent(lowOrder);
        container.initComponentPhase1();
        container.registerWebComponent(highOrder);

        assertSame(highOrder, container.webComponents.get("same"));
        verify(lowOrder).destroyComponent();
        verify(highOrder, never()).destroyComponent();
    }

    @Test
    void registerWebComponent_concurrentSameName_noLostOrDoubleDestroy() throws Exception {
        // 运行期动态注册（如 Actuator 端点）并发注册同名组件：compute 原子化保证
        // 最终容器只有一个存活组件，且不会出现丢失或误双重 destroy。
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        int threads = 8;
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threads);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);
        LifecycleWebComponent[] comps = new LifecycleWebComponent[threads];
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            comps[idx] = mock(LifecycleWebComponent.class);
            when(comps[idx].getComponentName()).thenReturn("same");
            when(comps[idx].getOrder()).thenReturn(100 + idx);
            pool.submit(() -> {
                try {
                    barrier.await();
                    container.registerWebComponent(comps[idx]);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(1, container.webComponents.size(), "并发同名注册后容器应只保留一个组件");
        // order 最小的 comps[0](100) 无论何时注册都会替换现存活者，且不会被更高 order 抢位，
        // 因此终态存活者必为 comps[0]，绝不能被 destroy
        assertSame(comps[0], container.webComponents.get("same"), "最高优先级组件应存活");
        verify(comps[0], never()).destroyComponent();
        // compute 原子合并保证：任何组件至多被 destroy 一次（无双 destroy、无丢失）；
        // 注意并发时序下被替换次数不确定，故不断言精确次数
        for (LifecycleWebComponent comp : comps) {
            long destroyCount = mockingDetails(comp).getInvocations().stream()
                    .filter(inv -> inv.getMethod().getName().equals("destroyComponent")).count();
            assertTrue(destroyCount <= 1, comp + " 不应被 destroy 超过一次，实际 " + destroyCount);
        }
    }

    @Test
    void getWebComponent_returnsFirstMatch() {
        WebComponentContainer container = new WebComponentContainer();
        WebComponent comp = mock(WebComponent.class);
        when(comp.getComponentName()).thenReturn("comp1");
        container.registerWebComponent(comp);

        WebComponent result = container.getWebComponent(WebComponent.class);
        assertSame(comp, result);
    }

    @Test
    void getWebComponent_noMatch_returnsNull() {
        WebComponentContainer container = new WebComponentContainer();
        assertNull(container.getWebComponent(WebComponent.class));
    }

    // ===== getWebComponent 快路径（单命中免 List 分配）的语义等价性 =====

    /**
     * 单命中：直接返回该组件，不应受其他不匹配组件干扰。
     */
    @Test
    void getWebComponent_singleMatch_returnsItself_regardlessOfOthers() {
        WebComponentContainer container = new WebComponentContainer();
        LifecycleWebComponent target = mock(LifecycleWebComponent.class);
        when(target.getComponentName()).thenReturn("target");
        // 另两个不同类型的组件不应被计入
        WebComponent other1 = mock(WebComponent.class);
        when(other1.getComponentName()).thenReturn("other1");
        WebComponent other2 = mock(WebComponent.class);
        when(other2.getComponentName()).thenReturn("other2");

        container.registerWebComponent(other1);
        container.registerWebComponent(target);
        container.registerWebComponent(other2);

        assertSame(target, container.getWebComponent(LifecycleWebComponent.class));
    }

    /**
     * 多命中必须仍然返回「按 @Order 排序后的第一个」—— 快路径只在单命中时生效，
     * 多命中若跳过排序会静默改变行为（返回容器遍历顺序的首个，而非最高优先级者）。
     */
    @Test
    void getWebComponent_multipleMatches_returnsHighestPriorityNotFirstRegistered() {
        WebComponentContainer container = new WebComponentContainer();

        // 先注册低优先级，确保「遍历顺序首个」与「order 最高」不是同一个
        WebComponent low = mock(WebComponent.class);
        when(low.getComponentName()).thenReturn("low");
        when(low.getOrder()).thenReturn(100);

        WebComponent high = mock(WebComponent.class);
        when(high.getComponentName()).thenReturn("high");
        when(high.getOrder()).thenReturn(10);

        WebComponent mid = mock(WebComponent.class);
        when(mid.getComponentName()).thenReturn("mid");
        when(mid.getOrder()).thenReturn(50);

        container.registerWebComponent(low);
        container.registerWebComponent(high);
        container.registerWebComponent(mid);

        assertSame(high, container.getWebComponent(WebComponent.class),
                "多命中时应按 @Order 返回最高优先级者，与 getWebComponents().get(0) 一致");
    }

    /**
     * 快路径与慢路径必须给出一致结果：对同一容器，getWebComponent(X) 恒等于
     * getWebComponents(X) 的首元素（若非空）。这是本次优化的核心不变量。
     */
    @Test
    void getWebComponent_matchesGetWebComponentsFirstElement() {
        WebComponentContainer container = new WebComponentContainer();
        for (int i = 0; i < 5; i++) {
            WebComponent c = mock(WebComponent.class);
            when(c.getComponentName()).thenReturn("c" + i);
            when(c.getOrder()).thenReturn(50 - i * 10);
            container.registerWebComponent(c);
        }

        List<WebComponent> all = container.getWebComponents(WebComponent.class);
        assertEquals(5, all.size());
        assertSame(all.get(0), container.getWebComponent(WebComponent.class),
                "getWebComponent 必须与 getWebComponents 的首元素一致");
    }

    /**
     * 零命中：即使容器内已有其他组件，按不匹配的类型查找仍返回 null。
     */
    @Test
    void getWebComponent_zeroMatchAmongOthers_returnsNull() {
        WebComponentContainer container = new WebComponentContainer();
        WebComponent existing = mock(WebComponent.class);
        when(existing.getComponentName()).thenReturn("existing");
        container.registerWebComponent(existing);

        assertNull(container.getWebComponent(LifecycleWebComponent.class));
    }

    /**
     * 运行期动态注册后查找必须能看到新组件（Actuator 端点场景）——
     * 快路径不得缓存结果、从而返回过期的 null。
     */
    @Test
    void getWebComponent_seesLateRegisteredComponent() {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        // 先查一次（无命中）
        assertNull(container.getWebComponent(LifecycleWebComponent.class));

        LifecycleWebComponent late = mock(LifecycleWebComponent.class);
        when(late.getComponentName()).thenReturn("late");
        container.registerWebComponent(late);

        assertSame(late, container.getWebComponent(LifecycleWebComponent.class),
                "运行期注册的组件必须立即可见");
    }

    @Test
    void getWebComponents_returnsAllMatching() {
        WebComponentContainer container = new WebComponentContainer();
        WebComponent comp1 = mock(WebComponent.class);
        when(comp1.getComponentName()).thenReturn("comp1");
        WebComponent comp2 = mock(WebComponent.class);
        when(comp2.getComponentName()).thenReturn("comp2");

        container.registerWebComponent(comp1);
        container.registerWebComponent(comp2);

        List<WebComponent> result = container.getWebComponents(WebComponent.class);
        assertEquals(2, result.size());
    }

    @Test
    void getWebComponents_returnsSortedByOrder() {
        WebComponentContainer container = new WebComponentContainer();
        WebComponent low = mock(WebComponent.class);
        when(low.getComponentName()).thenReturn("low");
        when(low.getOrder()).thenReturn(100);

        WebComponent high = mock(WebComponent.class);
        when(high.getComponentName()).thenReturn("high");
        when(high.getOrder()).thenReturn(10);

        container.registerWebComponent(low);
        container.registerWebComponent(high);

        List<WebComponent> result = container.getWebComponents(WebComponent.class);
        assertSame(high, result.get(0));
        assertSame(low, result.get(1));
    }

    @Test
    void getWebComponentWithDefault_existing_returnsExisting() {
        WebComponentContainer container = new WebComponentContainer();
        WebComponent existing = mock(WebComponent.class);
        when(existing.getComponentName()).thenReturn("existing");
        container.registerWebComponent(existing);

        WebComponent defaultComp = mock(WebComponent.class);
        WebComponent result = container.getWebComponentWithDefault(WebComponent.class, defaultComp);
        assertSame(existing, result);
    }

    @Test
    void getWebComponentWithDefault_notRegistered_returnsDefault() {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);
        WebComponent defaultComp = mock(WebComponent.class);
        when(defaultComp.getComponentName()).thenReturn("default");
        WebComponent result = container.getWebComponentWithDefault(WebComponent.class, defaultComp);
        assertSame(defaultComp, result);
    }

    @Test
    void phase1_callsInitOnLifecycleComponents() throws Exception {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        LifecycleWebComponent lifecycleComp = mock(LifecycleWebComponent.class);
        when(lifecycleComp.getComponentName()).thenReturn("lifecycle");
        container.registerWebComponent(lifecycleComp);

        container.initComponentPhase1();
        verify(lifecycleComp).initComponentPhase1();
    }

    @Test
    void phase2_callsInitOnLifecycleComponents() throws Exception {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        LifecycleWebComponent lifecycleComp = mock(LifecycleWebComponent.class);
        when(lifecycleComp.getComponentName()).thenReturn("lifecycle");
        container.registerWebComponent(lifecycleComp);

        container.initComponentPhase1();
        container.initComponentPhase2();
        verify(lifecycleComp).initComponentPhase2();
    }

    @Test
    void phase3_callsInitOnLifecycleComponents() throws Exception {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        LifecycleWebComponent lifecycleComp = mock(LifecycleWebComponent.class);
        when(lifecycleComp.getComponentName()).thenReturn("lifecycle");
        container.registerWebComponent(lifecycleComp);

        container.initComponentPhase1();
        container.initComponentPhase2();
        container.initComponentPhase3();
        verify(lifecycleComp).initComponentPhase3();
    }

    @Test
    void destroy_callsDestroyOnLifecycleComponents() throws Exception {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        LifecycleWebComponent lifecycleComp = mock(LifecycleWebComponent.class);
        when(lifecycleComp.getComponentName()).thenReturn("lifecycle");
        container.registerWebComponent(lifecycleComp);

        container.initComponentPhase1();
        container.initComponentPhase2();
        container.initComponentPhase3();
        container.destroyComponent();
        verify(lifecycleComp).destroyComponent();
    }

    @Test
    void destroy_fromMidPhase_stillCleansUpComponents() throws Exception {
        // 生命周期中途失败（只跑到 Phase1）后销毁，应仍能清理已初始化的组件，
        // 而不是停留在中间态无法回收资源
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        LifecycleWebComponent lifecycleComp = mock(LifecycleWebComponent.class);
        when(lifecycleComp.getComponentName()).thenReturn("lifecycle");
        container.registerWebComponent(lifecycleComp);

        container.initComponentPhase1();

        container.destroyComponent();
        verify(lifecycleComp).destroyComponent();
    }

    @Test
    void destroy_twice_isIdempotent() throws Exception {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        LifecycleWebComponent lifecycleComp = mock(LifecycleWebComponent.class);
        when(lifecycleComp.getComponentName()).thenReturn("lifecycle");
        container.registerWebComponent(lifecycleComp);

        container.initComponentPhase1();
        container.initComponentPhase2();
        container.initComponentPhase3();
        container.destroyComponent();
        container.destroyComponent();
        verify(lifecycleComp, times(1)).destroyComponent();
    }

    @Test
    void destroy_beforeInit_doesNotDestroyComponents() throws Exception {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();

        LifecycleWebComponent lifecycleComp = mock(LifecycleWebComponent.class);
        when(lifecycleComp.getComponentName()).thenReturn("lifecycle");
        container.registerWebComponent(lifecycleComp);

        // 从未初始化（State=NEW）时销毁：不应清理组件
        container.destroyComponent();
        verify(lifecycleComp, never()).destroyComponent();
    }

    @Test
    void resetAfterDestroy_returnsToNew() throws Exception {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);
        container.initComponentPhase1();
        container.destroyComponent();

        assertTrue(container.isDestroyed());
        assertTrue(container.resetAfterDestroy());
        // 复位后应能重新执行完整生命周期
        container.initWithWebContext(webContext);
        container.initComponentPhase1();
        container.initComponentPhase2();
        container.initComponentPhase3();
    }

    @Test
    void resetAfterDestroy_whenNotDestroyed_returnsFalse() throws Exception {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        assertFalse(container.isDestroyed());
        assertFalse(container.resetAfterDestroy());
    }

    @Test
    void autoRegisterWebComponent_addsRegistration() {
        WebComponentContainer container = new WebComponentContainer();
        container.autoRegisterWebComponent(WebComponent.class);
        assertTrue(container.autoRegisterComponentMap.containsKey(WebComponent.class));
    }

    @Test
    void registerWebComponent_afterInit_callsInitOnNewComponent() {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        LifecycleWebComponent lifecycleComp = mock(LifecycleWebComponent.class);
        when(lifecycleComp.getComponentName()).thenReturn("late");
        when(lifecycleComp.getOrder()).thenReturn(Ordered.LOWEST_PRECEDENCE);

        // After init, registering a LifecycleWebComponent should auto-init to current phase
        container.registerWebComponent(lifecycleComp);
        verify(lifecycleComp).initWithWebContext(webContext);
    }

    @Test
    void duplicate_registration_keepsHigherPriority() {
        WebComponentContainer container = new WebComponentContainer();
        WebComponent high = mock(WebComponent.class);
        when(high.getComponentName()).thenReturn("dup");
        when(high.getOrder()).thenReturn(5);

        WebComponent low = mock(WebComponent.class);
        when(low.getComponentName()).thenReturn("dup");
        when(low.getOrder()).thenReturn(15);

        container.registerWebComponent(high);

        // Register lower priority, should keep higher
        container.registerWebComponent(low);
        assertSame(high, container.webComponents.get("dup"));
    }

    @Test
    void nonWebLifecycleComponent_phaseMethodsNotCalled() throws Exception {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        // A WebComponent that is NOT a LifecycleWebComponent
        WebComponent plain = mock(WebComponent.class);
        when(plain.getComponentName()).thenReturn("plain");
        container.registerWebComponent(plain);

        container.initComponentPhase1();
        // Non-LifecycleWebComponent does not get phase1 called (initWithWebContext IS called during auto-init)
        // Verify no exception thrown during phase transition
    }

    @Test
    void phaseTransition_idempotent() throws Exception {
        WebComponentContainer container = new WebComponentContainer();
        WebContext webContext = createWebContext();
        container.initWithWebContext(webContext);

        LifecycleWebComponent comp = mock(LifecycleWebComponent.class);
        when(comp.getComponentName()).thenReturn("comp");
        container.registerWebComponent(comp);

        // Phase transitions
        container.initComponentPhase1();
        container.initComponentPhase1(); // second call should be no-op
        verify(comp, times(1)).initComponentPhase1();
    }
}
