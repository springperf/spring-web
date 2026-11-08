package io.springperf.web.core.async;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncTask;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.json.JacksonConverter;
import io.springperf.web.json.JsonConverter;

@ExtendWith(MockitoExtension.class)
class AsyncSupportRegistryTest {

    AsyncSupportRegistry registry;

    @Mock
    WebContext webContext;

    @Mock
    PerfAsyncWebRequest asyncWebRequest;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    @Mock
    ApplicationContext applicationContext;

    @BeforeEach
    void setUp() {
        registry = new AsyncSupportRegistry();
        lenient().when(webContext.getProps()).thenReturn(mock(ApplicationProperties.class));
    }

    @Test
    void getJsonConverter_afterInit_returnsConverter() throws Exception {
        JacksonConverter jacksonConverter = new JacksonConverter();
        when(webContext.getCtx()).thenReturn(applicationContext);
        when(applicationContext.getBeansOfType(CallableProcessingInterceptor.class)).thenReturn(Collections.emptyMap());
        when(applicationContext.getBeansOfType(DeferredResultProcessingInterceptor.class))
                .thenReturn(Collections.emptyMap());
        doReturn(null).when(webContext).getBeanFromCtx(com.fasterxml.jackson.databind.ObjectMapper.class);
        when(webContext.getWebComponentWithDefault(eq(JsonConverter.class), any(JsonConverter.class)))
                .thenReturn(jacksonConverter);

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();
        registry.initComponentPhase3();

        assertSame(jacksonConverter, registry.getJsonConverter());
    }

    @Test
    void startDeferredResultProcessing_setsTimeoutAndStarts() throws Exception {
        DeferredResult<String> deferredResult = new DeferredResult<>(5000L);

        registry.startDeferredResultProcessing(asyncWebRequest, deferredResult);

        verify(asyncWebRequest).setTimeout(5000L);
        verify(asyncWebRequest).startAsyncProcessing();
        verify(asyncWebRequest).addTimeoutHandler(any(Runnable.class));
        verify(asyncWebRequest).addErrorHandler(any());
        verify(asyncWebRequest).addCompletionHandler(any(Runnable.class));
    }

    @Test
    void startDeferredResultProcessing_negativeTimeout_passesThroughToWebRequest() throws Exception {
        // DeferredResult(-1L) 的 getTimeoutValue() 返回 -1L（非 null），
        // 与 Spring MVC 语义一致：-1 表示「无限期」，setTimeout(-1) 后不会实际调度超时任务
        DeferredResult<String> deferredResult = new DeferredResult<>(-1L);

        registry.startDeferredResultProcessing(asyncWebRequest, deferredResult);

        verify(asyncWebRequest).setTimeout(-1L);
        verify(asyncWebRequest).startAsyncProcessing();
        verify(asyncWebRequest).addTimeoutHandler(any(Runnable.class));
        verify(asyncWebRequest).addErrorHandler(any());
        verify(asyncWebRequest).addCompletionHandler(any(Runnable.class));
    }

    @Test
    void startDeferredResultProcessing_triggerResultHandler_dispatches() throws Exception {
        DeferredResult<String> deferredResult = new DeferredResult<>();

        doAnswer(invocation -> {
            Runnable callback = invocation.getArgument(0);
            callback.run();
            return null;
        }).when(asyncWebRequest).setAsyncReadyCallback(any(Runnable.class));

        registry.startDeferredResultProcessing(asyncWebRequest, deferredResult);

        // Set a result on the deferredResult to trigger the result handler
        deferredResult.setResult("test-result");

        verify(asyncWebRequest).setConcurrentResultAndDispatch("test-result");
    }

    @Test
    void initComponentPhase2_withBizPoolRegistry_usesDefaultPool() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BizPoolRegistry bizPoolRegistry = mock(BizPoolRegistry.class);
            when(bizPoolRegistry.getDefaultPool()).thenReturn(executor);
            when(webContext.getWebComponent(BizPoolRegistry.class)).thenReturn(bizPoolRegistry);
            when(webContext.getCtx()).thenReturn(applicationContext);
            when(applicationContext.getBeansOfType(CallableProcessingInterceptor.class))
                    .thenReturn(Collections.emptyMap());
            when(applicationContext.getBeansOfType(DeferredResultProcessingInterceptor.class))
                    .thenReturn(Collections.emptyMap());
            doReturn(null).when(webContext).getBeanFromCtx(com.fasterxml.jackson.databind.ObjectMapper.class);
            when(webContext.getWebComponentWithDefault(eq(JsonConverter.class), any(JsonConverter.class)))
                    .thenReturn(mock(JsonConverter.class));

            registry.initWithWebContext(webContext);
            registry.initComponentPhase1();
            registry.initComponentPhase2();

            // 提供 default 池时，defaultTaskExecutor 应包装该池（非 null），保证异步任务不会走兜底
            Object taskExecutor = getDefaultTaskExecutor(registry);
            assertNotNull(taskExecutor, "提供 default 业务线程池时 defaultTaskExecutor 应被设置");
            assertInstanceOf(org.springframework.core.task.AsyncTaskExecutor.class, taskExecutor);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void startCallableProcessing_withoutDefaultPool_doesNotNpe() throws Exception {
        // 无 default 业务线程池（BizPoolRegistry.getDefaultPool() 返回 null）→ defaultTaskExecutor = null
        BizPoolRegistry bizPoolRegistry = mock(BizPoolRegistry.class);
        when(bizPoolRegistry.getDefaultPool()).thenReturn(null);
        when(webContext.getWebComponent(BizPoolRegistry.class)).thenReturn(bizPoolRegistry);
        when(webContext.getCtx()).thenReturn(applicationContext);
        when(applicationContext.getBeansOfType(CallableProcessingInterceptor.class)).thenReturn(Collections.emptyMap());
        when(applicationContext.getBeansOfType(DeferredResultProcessingInterceptor.class))
                .thenReturn(Collections.emptyMap());
        doReturn(null).when(webContext).getBeanFromCtx(com.fasterxml.jackson.databind.ObjectMapper.class);
        when(webContext.getWebComponentWithDefault(eq(JsonConverter.class), any(JsonConverter.class)))
                .thenReturn(mock(JsonConverter.class));

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        // 捕获 asyncReadyCallback 并执行，验证内部 executor.submit 不会因 null 而 NPE
        doAnswer(invocation -> {
            Runnable callback = invocation.getArgument(0);
            callback.run();
            return null;
        }).when(asyncWebRequest).setAsyncReadyCallback(any(Runnable.class));

        WebAsyncTask<String> webAsyncTask = new WebAsyncTask<>(() -> "ok");

        assertDoesNotThrow(() -> registry.startCallableProcessing(asyncWebRequest, webAsyncTask));
        // 证明确实走到异步装配（回调被注册），而不是因 null 兜底提前返回
        verify(asyncWebRequest).setAsyncReadyCallback(any(Runnable.class));
    }

    @Test
    void startCallableProcessing_withoutDefaultPool_createsReusableFallbackExecutor() throws Exception {
        BizPoolRegistry bizPoolRegistry = mock(BizPoolRegistry.class);
        when(bizPoolRegistry.getDefaultPool()).thenReturn(null);
        when(webContext.getWebComponent(BizPoolRegistry.class)).thenReturn(bizPoolRegistry);
        when(webContext.getCtx()).thenReturn(applicationContext);
        when(applicationContext.getBeansOfType(CallableProcessingInterceptor.class)).thenReturn(Collections.emptyMap());
        when(applicationContext.getBeansOfType(DeferredResultProcessingInterceptor.class))
                .thenReturn(Collections.emptyMap());
        doReturn(null).when(webContext).getBeanFromCtx(com.fasterxml.jackson.databind.ObjectMapper.class);
        when(webContext.getWebComponentWithDefault(eq(JsonConverter.class), any(JsonConverter.class)))
                .thenReturn(mock(JsonConverter.class));

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        // 连续两次异步任务（均无显式 executor），兜底 executor 应懒加载且复用同一实例
        for (int i = 0; i < 2; i++) {
            doAnswer(invocation -> {
                Runnable callback = invocation.getArgument(0);
                callback.run();
                return null;
            }).when(asyncWebRequest).setAsyncReadyCallback(any(Runnable.class));
            WebAsyncTask<String> task = new WebAsyncTask<>(() -> "ok");
            assertDoesNotThrow(() -> registry.startCallableProcessing(asyncWebRequest, task));
        }

        Object fallback = getFallbackExecutor(registry);
        assertNotNull(fallback, "无默认池时兜底 executor 应被懒加载创建");
        assertInstanceOf(org.springframework.core.task.SimpleAsyncTaskExecutor.class, fallback);
    }

    /**
     * 超时回调必须 dispatch「超时结果」而不是任务结果。
     * <p>
     * 回归：拦截器链第一件事是 {@code cancel(true)}，被中断的任务会把 {@code InterruptedException} 写成结果，
     * 客户端因此拿到 500 而不是 503。占位后只有超时拦截器返回的 {@code AsyncRequestTimeoutException} 能落地。
     * </p>
     */
    @Test
    void startCallableProcessing_timeout_dispatchesTimeoutException() throws Exception {
        when(asyncWebRequest.claimTimeoutResult()).thenReturn(true);
        ArgumentCaptor<Runnable> timeoutHandler = ArgumentCaptor.forClass(Runnable.class);

        registry.startCallableProcessing(asyncWebRequest, new WebAsyncTask<>(100L, () -> "too-late"));

        verify(asyncWebRequest).addTimeoutHandler(timeoutHandler.capture());
        timeoutHandler.getValue().run();

        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(asyncWebRequest).setTimeoutResultAndDispatch(result.capture());
        assertInstanceOf(AsyncRequestTimeoutException.class, result.getValue(),
                "超时结果必须是 AsyncRequestTimeoutException（由 ResponseStatusExceptionResolver 映射为 503）");
        verify(asyncWebRequest, never()).setConcurrentResultAndDispatch(any());
    }

    /** 任务结果已先占位（claim 失败）：超时是迟到方，不得再写入任何结果。 */
    @Test
    void startCallableProcessing_timeout_whenClaimFails_doesNotDispatch() throws Exception {
        ArgumentCaptor<Runnable> timeoutHandler = ArgumentCaptor.forClass(Runnable.class);

        registry.startCallableProcessing(asyncWebRequest, new WebAsyncTask<>(100L, () -> "too-late"));

        verify(asyncWebRequest).addTimeoutHandler(timeoutHandler.capture());
        timeoutHandler.getValue().run();

        verify(asyncWebRequest, never()).setTimeoutResultAndDispatch(any());
        verify(asyncWebRequest, never()).setConcurrentResultAndDispatch(any());
    }

    private static Object getFallbackExecutor(AsyncSupportRegistry registry) throws Exception {
        java.lang.reflect.Field field = AsyncSupportRegistry.class.getDeclaredField("fallbackExecutor");
        field.setAccessible(true);
        return field.get(registry);
    }

    private static Object getDefaultTaskExecutor(AsyncSupportRegistry registry) throws Exception {
        java.lang.reflect.Field field = AsyncSupportRegistry.class.getDeclaredField("defaultTaskExecutor");
        field.setAccessible(true);
        return field.get(registry);
    }
}
