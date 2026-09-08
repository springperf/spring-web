package io.springperf.web.core.interceptor;

import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.Order;
import org.springframework.web.method.ControllerAdviceBean;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InterceptorRegistryTest {

    InterceptorRegistry registry;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    RequestContext requestContext;

    @BeforeEach
    void setUp() {
        registry = new InterceptorRegistry();
    }

    // ---- registerInterceptor ----

    @Test
    void registerInterceptor_createsRegistration() {
        HandlerInterceptor interceptor = new HandlerInterceptor() {};

        InterceptorRegistration registration = registry.registerInterceptor(interceptor);

        assertSame(interceptor, registration.getInterceptor());
        assertEquals(0, registration.getOrder());
    }

    @Test
    void registerInterceptor_withOrderAnnotation_appliesOrder() {
        @Order(42)
        class AnnotatedInterceptor implements HandlerInterceptor {}
        HandlerInterceptor interceptor = new AnnotatedInterceptor();

        InterceptorRegistration registration = registry.registerInterceptor(interceptor);

        assertEquals(42, registration.getOrder());
    }

    @Test
    void registerInterceptor_multiple_addsAll() throws Exception {
        HandlerInterceptor i1 = new HandlerInterceptor() {};
        HandlerInterceptor i2 = new HandlerInterceptor() {};

        registry.registerInterceptor(i1);
        registry.registerInterceptor(i2);

        // 验证两个拦截器都进入 registrations（通过 realGetInterceptors 走 ALWAYS 匹配路径）
        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(mappingContext.getPathRule()).thenReturn("/api/**");
        when(mappingContext.getCachedInterceptors()).thenReturn(null);
        // 模拟 fastAttributes：让 MappingResult.set/get 能正确存取（保存于同一声明 attribute）
        Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(any(RequestAttribute.class)))
                .thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            fastAttrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setAttribute(any(RequestAttribute.class), any());

        MappingResult matched = MappingResult.matched(mappingContext);
        MappingResult.set(request, matched);

        List<HandlerInterceptor> actual = registry.realGetInterceptors(request);

        ArgumentCaptor<List<HandlerInterceptor>> captor = ArgumentCaptor.forClass(List.class);
        verify(mappingContext).setCachedInterceptors(captor.capture());
        assertTrue(actual.stream().anyMatch(h -> h == i1), "i1 应进入拦截器列表");
        assertTrue(actual.stream().anyMatch(h -> h == i2), "i2 应进入拦截器列表");
    }

    // ---- 类级匹配（@ControllerAdvice + ControllerAdviceBean）----

    @Test
    void findControllerAdviceBean_noAnnotatedBean_returnsNull() {
        HandlerInterceptor interceptor = new HandlerInterceptor() {};
        assertNull(registry.findControllerAdviceBean(interceptor));
    }

    @Test
    void initCachedInterceptors_controllerAdviceScoped_matchesByBeanType() {
        HandlerInterceptor interceptor = new HandlerInterceptor() {};
        ControllerAdviceBean adviceBean = mock(ControllerAdviceBean.class);
        when(adviceBean.isApplicableToBeanType(ControllerA.class)).thenReturn(true);

        registry.registerInterceptor(interceptor).applyTo(adviceBean);

        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(mappingContext.getBeanType()).thenReturn((Class) ControllerA.class);
        when(mappingContext.getPathRule()).thenReturn("/api/**");

        List<HandlerInterceptor> interceptors = registry.initCachedInterceptors(mappingContext);
        assertTrue(interceptors.contains(interceptor), "匹配 ControllerA 时应包含该拦截器");
    }

    @Test
    void initCachedInterceptors_controllerAdviceScoped_nonMatchingType_excluded() {
        HandlerInterceptor interceptor = new HandlerInterceptor() {};
        ControllerAdviceBean adviceBean = mock(ControllerAdviceBean.class);
        when(adviceBean.isApplicableToBeanType(ControllerB.class)).thenReturn(false);

        registry.registerInterceptor(interceptor).applyTo(adviceBean);

        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(mappingContext.getBeanType()).thenReturn((Class) ControllerB.class);

        List<HandlerInterceptor> interceptors = registry.initCachedInterceptors(mappingContext);
        assertFalse(interceptors.contains(interceptor), "不匹配 ControllerB 时不应包含该拦截器");
    }

    @Test
    void initCachedInterceptors_controllerAdviceScoped_ignoresPathRule() {
        // 类级匹配的 registration 不使用 pathPatterns，即使 includePatterns 不匹配该路径也要按 beanType 判定
        HandlerInterceptor interceptor = new HandlerInterceptor() {};
        ControllerAdviceBean adviceBean = mock(ControllerAdviceBean.class);
        when(adviceBean.isApplicableToBeanType(ControllerA.class)).thenReturn(true);

        registry.registerInterceptor(interceptor)
                .addPathPatterns("/exclude/**")   // path 匹配应被忽略
                .applyTo(adviceBean);

        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(mappingContext.getBeanType()).thenReturn((Class) ControllerA.class);
        when(mappingContext.getPathRule()).thenReturn("/api/not-matching");

        List<HandlerInterceptor> interceptors = registry.initCachedInterceptors(mappingContext);
        assertTrue(interceptors.contains(interceptor), "类级匹配忽略 path 规则，应命中");
    }

    static class ControllerA {
    }

    static class ControllerB {
    }


    // ---- preHandle ----

    @Test
    void preHandle_noInterceptors_returnsTrue() throws Exception {
        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(null);

        assertTrue(registry.preHandle(request, response));
    }

    @Test
    void preHandle_allReturnTrue_returnsTrue() throws Exception {
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);
        when(i1.preHandle(any(), any(), any())).thenReturn(true);
        when(i2.preHandle(any(), any(), any())).thenReturn(true);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(Arrays.asList(i1, i2));

        assertTrue(registry.preHandle(request, response));
        verify(i1).preHandle(any(), any(), any());
        verify(i2).preHandle(any(), any(), any());
    }

    @Test
    void preHandle_interceptorReturnsFalse_stopsChain() throws Exception {
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);
        when(i1.preHandle(any(), any(), any())).thenReturn(false);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(Arrays.asList(i1, i2));

        assertFalse(registry.preHandle(request, response));
        verify(i1).preHandle(any(), any(), any());
        verify(i2, never()).preHandle(any(), any(), any());
    }

    @Test
    void preHandle_middleInterceptorReturnsFalse_onlyPassedOnesGetAfterCompletion() throws Exception {
        // 回归 P2 并发组 #1：preHandle=false 时仅对已通过 preHandle 的拦截器执行
        // afterCompletion。未进入的 i3 与自身返回 false 的 i2 都不得收到回调；
        // 修复前对所有拦截器调用会误触发 i2/i3 的 afterCompletion。
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);
        HandlerInterceptor i3 = mock(HandlerInterceptor.class);
        when(i1.preHandle(any(), any(), any())).thenReturn(true);
        when(i2.preHandle(any(), any(), any())).thenReturn(false);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE))
                .thenReturn(Arrays.asList(i1, i2, i3));

        assertFalse(registry.preHandle(request, response));
        verify(i1).preHandle(any(), any(), any());
        verify(i2).preHandle(any(), any(), any());
        verify(i3, never()).preHandle(any(), any(), any());
        // 已通过的 i1 收到 afterCompletion（异常为 null）；i2 本身与 i3 均不回调
        verify(i1).afterCompletion(any(), any(), any(), isNull());
        verify(i2, never()).afterCompletion(any(), any(), any(), any());
        verify(i3, never()).afterCompletion(any(), any(), any(), any());
    }

    @Test
    void preHandle_firstInterceptorReturnsFalse_noAfterCompletion() throws Exception {
        // 第一个拦截器即返回 false：没有任何拦截器通过 preHandle，
        // afterCompletion 一个都不应调用（对齐 Spring interceptorIndex 从 -1 开始）。
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        when(i1.preHandle(any(), any(), any())).thenReturn(false);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE))
                .thenReturn(Arrays.asList(i1, mock(HandlerInterceptor.class)));

        assertFalse(registry.preHandle(request, response));
        verify(i1, never()).afterCompletion(any(), any(), any(), any());
    }

    @Test
    void preHandle_throwsException_propagates() throws Exception {
        HandlerInterceptor interceptor = mock(HandlerInterceptor.class);
        when(interceptor.preHandle(any(), any(), any())).thenThrow(new RuntimeException("interceptor error"));

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(
                Collections.singletonList(interceptor));

        assertThrows(RuntimeException.class, () -> registry.preHandle(request, response));
    }

    @Test
    void preHandle_interceptorThrows_onlyPassedOnesGetAfterCompletionWithException() throws Exception {
        // 回归 #41：preHandle 抛异常时仅已通过 preHandle 的拦截器收到一次 afterCompletion(exception)，
        // 与 Spring applyPreHandle 抛异常 → doDispatch catch → processDispatchResult →
        // triggerAfterCompletion（interceptorIndex 只覆盖已通过者）语义一致。
        // 修复前 DispatcherHandler finally 会全量回调，未进入的拦截器也误收回调。
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);
        HandlerInterceptor i3 = mock(HandlerInterceptor.class);
        when(i1.preHandle(any(), any(), any())).thenReturn(true);
        when(i2.preHandle(any(), any(), any())).thenThrow(new RuntimeException("preHandle error"));

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE))
                .thenReturn(Arrays.asList(i1, i2, i3));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> registry.preHandle(request, response));
        assertEquals("preHandle error", ex.getMessage());
        // 已通过的 i1 收到 afterCompletion(exception)；抛异常的 i2 与未进入的 i3 均不回调
        verify(i1).afterCompletion(any(), any(), any(), eq(ex));
        verify(i2, never()).afterCompletion(any(), any(), any(), any());
        verify(i3, never()).afterCompletion(any(), any(), any(), any());
    }

    // ---- postHandle ----

    @Test
    void postHandle_callsAllInterceptors() throws Exception {
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);
        Object result = "testResult";

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(Arrays.asList(i1, i2));

        registry.postHandle(request, response, result);

        verify(i1).postHandle(any(), any(), any(), eq(result));
        verify(i2).postHandle(any(), any(), any(), eq(result));
    }

    @Test
    void postHandle_interceptorThrows_doesNotPropagate() throws Exception {
        HandlerInterceptor interceptor = mock(HandlerInterceptor.class);
        doThrow(new RuntimeException("post error")).when(interceptor).postHandle(any(), any(), any(), any());

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(
                Collections.singletonList(interceptor));

        registry.postHandle(request, response, "result");
    }

    // ---- afterCompletion ----

    @Test
    void afterCompletion_callsAllInterceptors() throws Exception {
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);
        Throwable ex = new RuntimeException("test error");

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(Arrays.asList(i1, i2));

        registry.afterCompletion(request, response, ex);

        verify(i1).afterCompletion(any(), any(), any(), eq(ex));
        verify(i2).afterCompletion(any(), any(), any(), eq(ex));
    }

    @Test
    void afterCompletion_interceptorThrows_doesNotPropagate() throws Exception {
        HandlerInterceptor interceptor = mock(HandlerInterceptor.class);
        doThrow(new RuntimeException("after error")).when(interceptor).afterCompletion(any(), any(), any(), any());

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(
                Collections.singletonList(interceptor));

        registry.afterCompletion(request, response, null);
    }

    @Test
    void afterCompletion_withNullException() throws Exception {
        HandlerInterceptor interceptor = mock(HandlerInterceptor.class);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(
                Collections.singletonList(interceptor));

        registry.afterCompletion(request, response, null);

        verify(interceptor).afterCompletion(any(), any(), any(), isNull());
    }

    // ---- afterConcurrentHandlingStarted ----

    @Test
    void afterConcurrentHandlingStarted_callsAllInterceptors() throws Exception {
        HandlerInterceptor i1 = mock(HandlerInterceptor.class);
        HandlerInterceptor i2 = mock(HandlerInterceptor.class);

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(Arrays.asList(i1, i2));

        registry.afterConcurrentHandlingStarted(request, response);

        verify(i1).afterConcurrentHandlingStarted(any(), any(), any());
        verify(i2).afterConcurrentHandlingStarted(any(), any(), any());
    }

    @Test
    void afterConcurrentHandlingStarted_interceptorThrows_doesNotPropagate() throws Exception {
        HandlerInterceptor interceptor = mock(HandlerInterceptor.class);
        doThrow(new RuntimeException("async error")).when(interceptor).afterConcurrentHandlingStarted(any(), any(), any());

        when(request.getRequestContext()).thenReturn(requestContext);
        when(requestContext.getAttribute(InterceptorRegistry.INTERCEPTORS_ATTRIBUTE)).thenReturn(
                Collections.singletonList(interceptor));

        registry.afterConcurrentHandlingStarted(request, response);
    }

    // ---- 404/405 无 mappingContext 时的路径级拦截器过滤 ----

    private static WebServerHttpRequest requestWithPath(String path) {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        RequestContext rc = mock(RequestContext.class);
        lenient().when(req.getRequestContext()).thenReturn(rc);
        lenient().when(req.getPath()).thenReturn(path);
        lenient().when(rc.getAttribute(any(io.springperf.web.http.RequestAttribute.class))).thenReturn(null);
        return req;
    }

    @Test
    void afterCompletion_noMappingContext_pathScopedInterceptorFilteredByPath() throws Exception {
        // P1-3 回归：404/405（无 handler）时路径级拦截器（includePatterns=/admin/**）
        // 必须按请求路径过滤，不得对任意 404 触发 afterCompletion。
        HandlerInterceptor pathInterceptor = mock(HandlerInterceptor.class);
        InterceptorRegistration reg = new InterceptorRegistration(pathInterceptor)
                .addPathPatterns("/admin/**");
        registry.registerWebComponent(reg);
        registry.initComponentPhase2();

        registry.afterCompletion(requestWithPath("/public/foo"), response, null);
        verify(pathInterceptor, never()).afterCompletion(any(), any(), any(), any());

        registry.afterCompletion(requestWithPath("/admin/foo"), response, null);
        verify(pathInterceptor).afterCompletion(any(), any(), any(), any());
    }

    @Test
    void afterCompletion_noMappingContext_globalInterceptorAlwaysRuns() throws Exception {
        // 全局拦截器（无 include/exclude）在 404 时不受路径过滤影响
        HandlerInterceptor global = mock(HandlerInterceptor.class);
        InterceptorRegistration reg = new InterceptorRegistration(global);
        registry.registerWebComponent(reg);
        registry.initComponentPhase2();

        registry.afterCompletion(requestWithPath("/public/foo"), response, null);
        verify(global).afterCompletion(any(), any(), any(), isNull());
    }
}
