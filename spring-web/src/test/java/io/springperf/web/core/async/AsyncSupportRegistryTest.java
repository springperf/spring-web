package io.springperf.web.core.async;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import io.springperf.web.json.JacksonConverter;
import io.springperf.web.json.JsonConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.context.request.async.WebAsyncTask;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

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

    /**
     * 异步超时键对齐 Spring Boot：读 {@code spring.mvc.async.request-timeout}（Duration 语义：{@code 30s}/{@code 1m}，
     * 裸数字按毫秒）。
     * <p>
     * 2.7.x 原先读自造的 {@code server.async.timeout}，该键在 master 上已不存在；不切键则
     * {@code spring.mvc.async.request-timeout} 是死键（配置了也不生效）。
     * </p>
     */
    @Test
    void initWithWebContext_readsSpringMvcAsyncRequestTimeout() {
        ApplicationProperties props = mock(ApplicationProperties.class);
        when(webContext.getProps()).thenReturn(props);
        when(props.getDurationMillis(PropertiesConstant.ASYNC_REQUEST_TIMEOUT,
                PropertiesConstant.ASYNC_REQUEST_TIMEOUT_DEFAULT)).thenReturn(5000L);
        when(webContext.getCtx()).thenReturn(applicationContext);
        when(applicationContext.getBeansOfType(CallableProcessingInterceptor.class)).thenReturn(Collections.emptyMap());
        when(applicationContext.getBeansOfType(DeferredResultProcessingInterceptor.class))
                .thenReturn(Collections.emptyMap());

        registry.initWithWebContext(webContext);

        assertEquals(5000L, registry.getDefaultTimeout());
        verify(props).getDurationMillis(PropertiesConstant.ASYNC_REQUEST_TIMEOUT,
                PropertiesConstant.ASYNC_REQUEST_TIMEOUT_DEFAULT);
    }

    @Test
    void getJsonConverter_afterInit_returnsConverter() throws Exception {
        JacksonConverter jacksonConverter = new JacksonConverter();
        when(webContext.getCtx()).thenReturn(applicationContext);
        when(applicationContext.getBeansOfType(CallableProcessingInterceptor.class)).thenReturn(Collections.emptyMap());
        when(applicationContext.getBeansOfType(DeferredResultProcessingInterceptor.class)).thenReturn(Collections.emptyMap());
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
            when(applicationContext.getBeansOfType(CallableProcessingInterceptor.class)).thenReturn(Collections.emptyMap());
            when(applicationContext.getBeansOfType(DeferredResultProcessingInterceptor.class)).thenReturn(Collections.emptyMap());
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
        lenient().when(bizPoolRegistry.getDefaultPool()).thenReturn(null);
        lenient().when(webContext.getWebComponent(BizPoolRegistry.class)).thenReturn(bizPoolRegistry);
        lenient().when(webContext.getCtx()).thenReturn(applicationContext);
        lenient().when(applicationContext.getBeansOfType(CallableProcessingInterceptor.class)).thenReturn(Collections.emptyMap());
        lenient().when(applicationContext.getBeansOfType(DeferredResultProcessingInterceptor.class)).thenReturn(Collections.emptyMap());
        lenient().doReturn(null).when(webContext).getBeanFromCtx(com.fasterxml.jackson.databind.ObjectMapper.class);
        lenient().when(webContext.getWebComponentWithDefault(eq(JsonConverter.class), any(JsonConverter.class)))
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
    }

    @Test
    void startCallableProcessing_withoutDefaultPool_createsReusableFallbackExecutor() throws Exception {
        BizPoolRegistry bizPoolRegistry = mock(BizPoolRegistry.class);
        lenient().when(bizPoolRegistry.getDefaultPool()).thenReturn(null);
        lenient().when(webContext.getWebComponent(BizPoolRegistry.class)).thenReturn(bizPoolRegistry);
        lenient().when(webContext.getCtx()).thenReturn(applicationContext);
        lenient().when(applicationContext.getBeansOfType(CallableProcessingInterceptor.class)).thenReturn(Collections.emptyMap());
        lenient().when(applicationContext.getBeansOfType(DeferredResultProcessingInterceptor.class)).thenReturn(Collections.emptyMap());
        lenient().doReturn(null).when(webContext).getBeanFromCtx(com.fasterxml.jackson.databind.ObjectMapper.class);
        lenient().when(webContext.getWebComponentWithDefault(eq(JsonConverter.class), any(JsonConverter.class)))
                .thenReturn(mock(JsonConverter.class));

        registry.initWithWebContext(webContext);
        registry.initComponentPhase1();
        registry.initComponentPhase2();

        // 打桩必须放在循环外：兜底 SimpleAsyncTaskExecutor.execute() 会立刻起工作线程跑 Callable，
        // 而该线程会回调同一个 mock（setConcurrentResultAndDispatch 等）。若在循环内、且上一轮
        // 工作线程仍在活动时再次对 mock 打桩（doAnswer(...).when(...)），Mockito 的 stubbing 状态与
        // 并发调用相撞，会抛出 UnfinishedStubbingException（实测：本方法单跑 3 次中 2 次复现；
        // master 侧同一写法同样存在，属既有测试竞态）。提到循环外后，循环期间不再有 stubbing 建立。
        doAnswer(invocation -> {
            Runnable callback = invocation.getArgument(0);
            callback.run();
            return null;
        }).when(asyncWebRequest).setAsyncReadyCallback(any(Runnable.class));

        // 连续两次异步任务（均无显式 executor），兜底 executor 应懒加载且复用同一实例
        for (int i = 0; i < 2; i++) {
            WebAsyncTask<String> task = new WebAsyncTask<>(() -> "ok");
            assertDoesNotThrow(() -> registry.startCallableProcessing(asyncWebRequest, task));
        }

        Object fallback = getFallbackExecutor(registry);
        assertNotNull(fallback, "无默认池时兜底 executor 应被懒加载创建");
        assertInstanceOf(org.springframework.core.task.SimpleAsyncTaskExecutor.class, fallback);
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