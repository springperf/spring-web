package io.springperf.web.context;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebComponentWrapperUtilsTest {

    static class TestService {
        public String serve() { return "ok"; }
    }

    static class AnotherService {
    }

    private WebComponentContainer container;

    @BeforeEach
    void setUp() {
        container = new WebComponentContainer();
        WebContext webContext = mock(WebContext.class);
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(webContext.getCtx()).thenReturn(ctx);
        when(ctx.getBeansOfType(any())).thenReturn(Collections.emptyMap());
        container.initWithWebContext(webContext);
    }

    @Test
    void getComponent_noMatch_returnsNull() {
        assertNull(WebComponentWrapperUtils.getComponent(container, TestService.class));
    }

    @Test
    void getComponent_withWebComponent_returnsIt() {
        WebComponent comp = mock(WebComponent.class);
        when(comp.getComponentName()).thenReturn("comp");
        container.registerWebComponent(comp);

        WebComponent result = WebComponentWrapperUtils.getComponent(container, WebComponent.class);
        assertSame(comp, result);
    }

    @Test
    void getComponents_withMultiple_returnsAll() {
        WebComponent comp1 = mock(WebComponent.class);
        when(comp1.getComponentName()).thenReturn("c1");
        WebComponent comp2 = mock(WebComponent.class);
        when(comp2.getComponentName()).thenReturn("c2");
        container.registerWebComponent(comp1);
        container.registerWebComponent(comp2);

        List<WebComponent> result = WebComponentWrapperUtils.getComponents(container, WebComponent.class);
        assertEquals(2, result.size());
    }

    @Test
    void getComponents_empty_returnsEmptyList() {
        List<WebComponent> result = WebComponentWrapperUtils.getComponents(container, WebComponent.class);
        assertTrue(result.isEmpty());
    }

    @Test
    void registerComponent_withWebComponent_registersDirectly() {
        WebComponent comp = mock(WebComponent.class);
        when(comp.getComponentName()).thenReturn("direct");
        WebComponentWrapperUtils.registerComponent(container, comp);
        assertNotNull(container.webComponents.get("direct"));
    }

    @Test
    void registerComponent_withNonWebComponent_wrapsIt() {
        TestService service = new TestService();
        WebComponentWrapperUtils.registerComponent(container, service);
        assertNotNull(container.webComponents.get("TestService"));
    }

    @Test
    void getComponentWithDefault_noMatch_returnsDefault() {
        TestService defaultService = new TestService();
        TestService result = WebComponentWrapperUtils.getComponentWithDefault(container, TestService.class, defaultService);
        assertSame(defaultService, result);
    }

    @Test
    void getComponentWithDefault_hasMatch_returnsExisting() {
        WebComponent comp = mock(WebComponent.class);
        when(comp.getComponentName()).thenReturn("existing");
        container.registerWebComponent(comp);

        WebComponent defaultComp = mock(WebComponent.class);
        WebComponent result = WebComponentWrapperUtils.getComponentWithDefault(container, WebComponent.class, defaultComp);
        assertSame(comp, result);
    }

    @Test
    void initRealComponentList_clearsAndFills() {
        WebComponent comp = mock(WebComponent.class);
        when(comp.getComponentName()).thenReturn("c1");
        container.registerWebComponent(comp);

        List<WebComponent> list = new java.util.ArrayList<>();
        list.add(mock(WebComponent.class)); // junk entry

        WebComponentWrapperUtils.initRealComponentList(container, list, WebComponent.class);
        assertEquals(1, list.size());
        assertSame(comp, list.get(0));
    }

    @Test
    void getComponents_withWrappedService_unwrapsThem() {
        TestService service = new TestService();
        WebComponentWrapperUtils.registerComponent(container, service);

        List<TestService> result = WebComponentWrapperUtils.getComponents(container, TestService.class);
        assertEquals(1, result.size());
        assertSame(service, result.get(0));
    }
}