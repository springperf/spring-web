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
        assertTrue(result.containsAll(java.util.Arrays.asList(comp1, comp2)), "应返回全部已注册组件，而不是任意两个对象");
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
        assertSame(comp, container.webComponents.get("direct"), "WebComponent 应原样注册，不得再包装");
    }

    @Test
    void registerComponent_withNonWebComponent_wrapsIt() {
        TestService service = new TestService();
        WebComponentWrapperUtils.registerComponent(container, service);

        WebComponent stored = container.webComponents.get("TestService");
        assertInstanceOf(WebComponentWrapper.class, stored, "非 WebComponent 必须包装后注册");
        assertSame(service, ((WebComponentWrapper<?>) stored).getComponent(), "包装器必须持有原始对象");
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