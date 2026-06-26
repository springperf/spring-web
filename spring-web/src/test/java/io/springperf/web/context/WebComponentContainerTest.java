package io.springperf.web.context;

import io.springperf.web.core.DispatcherHandler;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;

import java.util.Collections;
import java.util.List;

import static io.springperf.web.context.PropertiesConstant.CONTEXT_PATH;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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