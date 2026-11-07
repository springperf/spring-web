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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        // 默认值是编译期常量，宽松匹配第二个参数可避免默认值调整后桩参数失配
        when(props.get(eq(ViewProperties.ENGINE), any())).thenReturn(engine);
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
        assertNull(headers.getFirst(HttpHeaders.CONTENT_TYPE), "302 无响应体，不应声明 text/html");
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

        assertThrows(IllegalArgumentException.class, () -> resolver.resolveReturnValue("missing", null, req, resp));

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
    void engineNone_mixedList_failsFast() throws Exception {
        // none 只能单独使用：混写必须在启动期中断，不能静默跳过 fail-fast
        stubEngine("none,thymeleaf");
        when(viewResolverRegistry.hasViewResolvers()).thenReturn(false);

        ViewReturnValueResolver resolver = buildResolver();
        IllegalStateException ex = assertThrows(IllegalStateException.class, resolver::initComponentPhase2);
        assertTrue(ex.getMessage().contains("must be used alone"));
    }

    @Test
    void engineInvalidValue_failsFast() throws Exception {
        stubEngine("thymeleaf,typo");
        when(viewResolverRegistry.hasViewResolvers()).thenReturn(false);

        ViewReturnValueResolver resolver = buildResolver();
        IllegalStateException ex = assertThrows(IllegalStateException.class, resolver::initComponentPhase2);
        assertTrue(ex.getMessage().contains(ViewProperties.ENGINE));
        assertTrue(ex.getMessage().contains("typo"));
    }

    @Test
    void withResolver_noFailFast() throws Exception {
        // 有 ViewResolver 注册：String 视图方法正常放行（无需读取引擎配置）
        when(viewResolverRegistry.hasViewResolvers()).thenReturn(true);

        ViewReturnValueResolver resolver = buildResolver();
        assertDoesNotThrow(resolver::initComponentPhase2);
    }

    // ==================== 统一渲染入口 ====================

    @Test
    void renderView_success_marksHandledAfterRender() throws Exception {
        View view = mock(View.class);
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);

        ViewReturnValueResolver.renderView(view, Collections.emptyMap(), req, resp);

        verify(view).render(any(), eq(req), eq(resp));
        verify(resp).setHandled();
        verify(resp, org.mockito.Mockito.never()).resetBuffer();
    }

    @Test
    void renderView_failure_resetsBufferAndDoesNotMarkHandled() throws Exception {
        IllegalStateException boom = new IllegalStateException("render failed");
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        when(resp.getBody()).thenReturn(body);
        View view = (model, request, response) -> {
            response.getBody().write("PARTIAL-HTML".getBytes(StandardCharsets.UTF_8));
            throw boom;
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> ViewReturnValueResolver.renderView(view, Collections.emptyMap(), req, resp));

        assertSame(boom, thrown, "渲染异常必须原样抛出");
        assertEquals("PARTIAL-HTML", new String(body.toByteArray(), StandardCharsets.UTF_8),
                "用例须先写出半截页面，否则无法证明 resetBuffer 的必要性");
        verify(resp).resetBuffer();
        verify(resp, org.mockito.Mockito.never()).setHandled();
    }

    // ==================== Content-Type ====================

    @Test
    void applyContentType_customCharset_syncsResponseEncoding() {
        View view = mock(View.class);
        when(view.getContentType()).thenReturn("text/html;charset=ISO-8859-1");
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        when(resp.getHeaders()).thenReturn(headers);

        ViewReturnValueResolver.applyContentType(view, resp);

        assertEquals("text/html;charset=ISO-8859-1", headers.getFirst(HttpHeaders.CONTENT_TYPE));
        verify(resp).setCharacterEncoding(StandardCharsets.ISO_8859_1);
    }

    @Test
    void applyContentType_nullContentType_fallsBackToResponseEncoding() {
        View view = mock(View.class);
        when(view.getContentType()).thenReturn(null);
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        when(resp.getHeaders()).thenReturn(headers);
        when(resp.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_16BE);

        ViewReturnValueResolver.applyContentType(view, resp);

        assertEquals("text/html;charset=UTF-16BE", headers.getFirst(HttpHeaders.CONTENT_TYPE));
        assertSame(StandardCharsets.UTF_16BE, resp.getCharacterEncoding());
    }

    @Test
    void applyContentType_customContentTypeWithoutCharset_appendsResponseCharset() {
        View view = mock(View.class);
        when(view.getContentType()).thenReturn("application/xhtml+xml");
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        when(resp.getHeaders()).thenReturn(headers);
        when(resp.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_8);

        ViewReturnValueResolver.applyContentType(view, resp);

        assertEquals("application/xhtml+xml;charset=UTF-8", headers.getFirst(HttpHeaders.CONTENT_TYPE),
                "头必须声明 Writer 实际使用的编码，否则浏览器按自身默认编码解析");
        verify(resp).setCharacterEncoding(StandardCharsets.UTF_8);
    }

    @Test
    void applyContentType_customContentTypeWithoutCharset_usesNonDefaultResponseCharset() {
        View view = mock(View.class);
        when(view.getContentType()).thenReturn("application/xhtml+xml");
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        when(resp.getHeaders()).thenReturn(headers);
        when(resp.getCharacterEncoding()).thenReturn(StandardCharsets.UTF_16BE);

        ViewReturnValueResolver.applyContentType(view, resp);

        assertEquals("application/xhtml+xml;charset=UTF-16BE", headers.getFirst(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    void applyContentType_redirect_removesContentType() {
        WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "text/html");
        when(resp.getHeaders()).thenReturn(headers);

        ViewReturnValueResolver.applyContentType(new RedirectView("/home"), resp);

        assertNull(headers.getFirst(HttpHeaders.CONTENT_TYPE));
    }

    static class TestController {
        public String view() {
            return "hello";
        }
    }
}
