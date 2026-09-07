package io.springperf.web.view.retval;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.view.ViewProperties;
import io.springperf.web.view.ViewResolverRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.method.HandlerMethod;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ViewReturnValueResolverTest {

    @Mock
    WebContext webContext;
    @Mock
    MappingRegistry mappingRegistry;
    @Mock
    ViewResolverRegistry viewResolverRegistry;
    @Mock
    ApplicationProperties props;

    private ViewReturnValueResolver buildResolver() throws Exception {
        PathMappingContext mapping = new PathMappingContext(
                new HandlerMethod(new TestController(), TestController.class.getMethod("view")),
                Collections.emptyList(), "/view");
        when(mappingRegistry.getMappingContextList()).thenReturn(Collections.singletonList(mapping));
        when(webContext.getWebComponent(MappingRegistry.class)).thenReturn(mappingRegistry);
        when(webContext.getWebComponentWithDefault(eq(ViewResolverRegistry.class), any()))
                .thenReturn(viewResolverRegistry);

        ViewReturnValueResolver resolver = new ViewReturnValueResolver();
        resolver.initWithWebContext(webContext);
        return resolver;
    }

    private void stubEngine(String engine) {
        when(webContext.getProps()).thenReturn(props);
        when(props.get(ViewProperties.ENGINE, ViewProperties.ENGINE_DEFAULT)).thenReturn(engine);
    }

    @Test
    void engineNotDisabled_withoutResolver_failsFast() throws Exception {
        // 未显式禁用引擎且无 ViewResolver：String 视图方法应触发 fail-fast
        stubEngine(ViewProperties.ENGINE_DEFAULT);
        when(viewResolverRegistry.hasViewResolvers()).thenReturn(false);

        ViewReturnValueResolver resolver = buildResolver();
        assertThrows(IllegalStateException.class, resolver::initComponentPhase2);
    }

    @Test
    void engineNone_withoutResolver_skipsFailFast() throws Exception {
        // 显式 engine=none：仅 redirect 场景，不应 fail-fast
        stubEngine("none");
        when(viewResolverRegistry.hasViewResolvers()).thenReturn(false);

        ViewReturnValueResolver resolver = buildResolver();
        assertDoesNotThrow(resolver::initComponentPhase2);
    }

    @Test
    void engineNone_mixedList_skipsFailFast() throws Exception {
        // 逗号分隔多选：含 none 时视为禁用全部模板引擎
        stubEngine("none,thymeleaf");
        when(viewResolverRegistry.hasViewResolvers()).thenReturn(false);

        ViewReturnValueResolver resolver = buildResolver();
        assertDoesNotThrow(resolver::initComponentPhase2);
    }

    @Test
    void withResolver_noFailFast() throws Exception {
        // 有 ViewResolver 注册：String 视图方法正常放行（无需读取引擎配置）
        when(viewResolverRegistry.hasViewResolvers()).thenReturn(true);

        ViewReturnValueResolver resolver = buildResolver();
        assertDoesNotThrow(resolver::initComponentPhase2);
    }

    static class TestController {
        public String view() {
            return "hello";
        }
    }
}
