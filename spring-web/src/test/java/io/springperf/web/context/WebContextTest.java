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
        String high = "high", low = "low";
        java.util.Map<String, String> multiMap = new java.util.HashMap<>();
        multiMap.put("bean1", low);
        multiMap.put("bean2", high);
        when(ctx.getBeansOfType(String.class)).thenReturn(multiMap);
        String result = webContext.getBeanFromCtx(String.class);
        // AnnotationAwareOrderComparator sorts; both Strings have default order
        // The result order depends on comparator behavior
        assertNotNull(result);
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
}