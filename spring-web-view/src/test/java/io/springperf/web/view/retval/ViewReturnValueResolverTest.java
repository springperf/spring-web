package io.springperf.web.view.retval;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.view.RedirectView;
import io.springperf.web.view.View;
import io.springperf.web.view.ViewProperties;
import io.springperf.web.view.ViewResolverRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        // mappingRegistry 相关 stub 仅 initComponentPhase2 使用，运行时用例不需要 → lenient
        org.mockito.Mockito.lenient().when(mappingRegistry.getMappingContextList())
                .thenReturn(Collections.singletonList(mapping));
        org.mockito.Mockito.lenient().when(webContext.getWebComponent(MappingRegistry.class))
                .thenReturn(mappingRegistry);
        when(webContext.getWebComponentWithDefault(eq(ViewResolverRegistry.class), any()))
                .thenReturn(viewResolverRegistry);

        ViewReturnValueResolver resolver = new ViewReturnValueResolver();
        resolver.initWithWebContext(webContext);
        return resolver;
    }

    @SuppressWarnings("unchecked")
    private static io.springperf.web.core.mapping.MappingCacheKey<Boolean> viewNameKey() throws Exception {
        java.lang.reflect.Field field = ViewReturnValueResolver.class.getDeclaredField("VIEW_NAME_KEY");
        field.setAccessible(true);
        return (io.springperf.web.core.mapping.MappingCacheKey<Boolean>) field.get(null);
    }

    private void stubEngine(String engine) {
        when(webContext.getProps()).thenReturn(props);
        when(props.get(ViewProperties.ENGINE, ViewProperties.ENGINE_DEFAULT)).thenReturn(engine);
    }

    // ==================== 运行时主路径 ====================

    @Test
    void supportsReturnType_usesViewNameCacheKey() throws Exception {
        ViewReturnValueResolver resolver = buildResolver();
        MappingHandlerMethod mapping = mock(MappingHandlerMethod.class);
        when(mapping.get(viewNameKey())).thenReturn(Boolean.TRUE);
        assertTrue(resolver.supportsReturnType(null, mapping));

        when(mapping.get(viewNameKey())).thenReturn(null);
        assertFalse(resolver.supportsReturnType(null, mapping));
    }

    @Test
    void supportsReturnValue_redirectRequiresViewMethod() throws Exception {
        // redirect: 前缀仅对"视图方法"生效（与 plain view 一致）：@ResponseBody 方法返回
        // "redirect:/x" 字符串应作为 JSON 输出，不能被劫持成 302
        ViewReturnValueResolver resolver = new ViewReturnValueResolver();
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        RequestContext ctx = mock(RequestContext.class);
        when(req.getRequestContext()).thenReturn(ctx);
        Map<RequestAttribute<?>, Object> attrs = new HashMap<>();
        when(ctx.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ctx).setAttribute(any(RequestAttribute.class), any());

        // 无匹配 MappingContext（非视图方法）→ 不支持
        assertFalse(resolver.supportsReturnValue("redirect:/home", req, null));

        // 视图方法（VIEW_NAME_KEY=TRUE）→ 支持
        PathMappingContext mapping = mock(PathMappingContext.class);
        when(mapping.get(viewNameKey())).thenReturn(Boolean.TRUE);
        MappingResult.set(req, MappingResult.matched(mapping));
        assertTrue(resolver.supportsReturnValue("redirect:/home", req, null));
    }

    @Test
    void supportsReturnValue_nonStringFalse() {
        ViewReturnValueResolver resolver = new ViewReturnValueResolver();
        assertFalse(resolver.supportsReturnValue(123, null, null));
    }

    @Test
    void supportsReturnValue_plainViewRequiresViewNameKey() throws Exception {
        ViewReturnValueResolver resolver = new ViewReturnValueResolver();
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        RequestContext ctx = mock(RequestContext.class);
        when(req.getRequestContext()).thenReturn(ctx);
        Map<RequestAttribute<?>, Object> attrs = new HashMap<>();
        when(ctx.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ctx).setAttribute(any(RequestAttribute.class), any());

        // 无 MappingContext → 不支持
        assertFalse(resolver.supportsReturnValue("home", req, null));

        // 有 matched MappingContext 且 VIEW_NAME_KEY=TRUE → 支持
        PathMappingContext mapping = mock(PathMappingContext.class);
        when(mapping.get(viewNameKey())).thenReturn(Boolean.TRUE);
        MappingResult.set(req, MappingResult.matched(mapping));
        assertTrue(resolver.supportsReturnValue("home", req, null));
    }

    @Test
    void resolveReturnValue_redirect_sets302AndLocation() throws Exception {
        ViewReturnValueResolver resolver = buildResolver();
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        RequestContext ctx = mock(RequestContext.class);
        HttpHeaders headers = new HttpHeaders();
        when(req.getRequestContext()).thenReturn(ctx);
        Map<RequestAttribute<?>, Object> attrs = new HashMap<>();
        when(ctx.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ctx).setAttribute(any(RequestAttribute.class), any());
        when(resp.getHeaders()).thenReturn(headers);
        when(req.getWebContext()).thenReturn(webContext);
        when(webContext.getContextPath()).thenReturn("/api");

        resolver.resolveReturnValue("redirect:/home", null, req, resp);

        verify(resp).setHandled();
        verify(resp).setStatusCode(HttpStatus.FOUND);
        assertTrue(headers.getFirst(HttpHeaders.LOCATION).contains("/api/home"),
                "302 Location 应含 context-path，实际: " + headers.getFirst(HttpHeaders.LOCATION));
        assertTrue(headers.getFirst(HttpHeaders.CONTENT_TYPE).startsWith("text/html"));
    }

    @Test
    void resolveReturnValue_plainView_renders() throws Exception {
        ViewReturnValueResolver resolver = buildResolver();
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        RequestContext ctx = mock(RequestContext.class);
        HttpHeaders headers = new HttpHeaders();
        View view = mock(View.class);
        when(view.getContentType()).thenReturn("text/html;charset=UTF-8");
        when(req.getRequestContext()).thenReturn(ctx);
        Map<RequestAttribute<?>, Object> attrs = new HashMap<>();
        when(ctx.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> attrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            attrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(ctx).setAttribute(any(RequestAttribute.class), any());
        when(resp.getHeaders()).thenReturn(headers);
        when(viewResolverRegistry.resolve(eq("home"), eq(req))).thenReturn(view);

        resolver.resolveReturnValue("home", null, req, resp);

        verify(resp).setHandled();
        verify(view).render(any(), eq(req), eq(resp));
        assertEquals("text/html;charset=UTF-8", headers.getFirst(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    void resolveReturnValue_noView_throwsIllegalArgument() throws Exception {
        // 视图名无法解析：必须 fail（500），而非静默返回 200 + 空白 body
        ViewReturnValueResolver resolver = buildResolver();
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        when(viewResolverRegistry.resolve(eq("missing"), eq(req))).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveReturnValue("missing", null, req, resp));

        verify(resp, org.mockito.Mockito.never()).setHandled();
        verify(resp, org.mockito.Mockito.never()).setStatusCode(any());
    }

    // ==================== 配置期 fail-fast ====================

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
