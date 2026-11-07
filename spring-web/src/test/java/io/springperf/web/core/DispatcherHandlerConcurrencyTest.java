package io.springperf.web.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.ArgumentResolverRegistry;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.exception.ExceptionRegistry;
import io.springperf.web.core.filter.WebFilterRegistry;
import io.springperf.web.core.interceptor.InterceptorRegistry;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.http.RequestAttribute;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;

class DispatcherHandlerConcurrencyTest {

    private DispatcherHandler handler;
    private WebContext webContext;
    private MappingRegistry mappingRegistry;
    private ExceptionRegistry exceptionRegistry;
    private ArgumentResolverRegistry argumentResolverRegistry;
    private ReturnValueResolverRegistry returnValueResolverRegistry;
    private CorsRegistry corsRegistry;
    private InterceptorRegistry interceptorRegistry;
    private BizPoolRegistry bizPoolRegistry;
    private WebFilterRegistry webFilterRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        handler = new DispatcherHandler();
        webContext = mock(WebContext.class);
        mappingRegistry = mock(MappingRegistry.class);
        exceptionRegistry = mock(ExceptionRegistry.class);
        argumentResolverRegistry = mock(ArgumentResolverRegistry.class);
        returnValueResolverRegistry = mock(ReturnValueResolverRegistry.class);
        corsRegistry = mock(CorsRegistry.class);
        interceptorRegistry = mock(InterceptorRegistry.class);
        bizPoolRegistry = mock(BizPoolRegistry.class);

        when(webContext.getWebComponentWithDefault(eq(MappingRegistry.class), any(MappingRegistry.class)))
                .thenReturn(mappingRegistry);
        when(webContext.getWebComponentWithDefault(eq(ExceptionRegistry.class), any(ExceptionRegistry.class)))
                .thenReturn(exceptionRegistry);
        when(webContext.getWebComponentWithDefault(eq(ArgumentResolverRegistry.class),
                any(ArgumentResolverRegistry.class))).thenReturn(argumentResolverRegistry);
        when(webContext.getWebComponentWithDefault(eq(ReturnValueResolverRegistry.class),
                any(ReturnValueResolverRegistry.class))).thenReturn(returnValueResolverRegistry);
        when(webContext.getWebComponentWithDefault(eq(CorsRegistry.class), any(CorsRegistry.class)))
                .thenReturn(corsRegistry);
        when(webContext.getWebComponentWithDefault(eq(InterceptorRegistry.class), any(InterceptorRegistry.class)))
                .thenReturn(interceptorRegistry);
        when(webContext.getWebComponentWithDefault(eq(AsyncSupportRegistry.class), any(AsyncSupportRegistry.class)))
                .thenReturn(mock(AsyncSupportRegistry.class));
        when(webContext.getWebComponentWithDefault(eq(BizPoolRegistry.class), any(BizPoolRegistry.class)))
                .thenReturn(bizPoolRegistry);

        webFilterRegistry = mock(WebFilterRegistry.class);
        when(webContext.getWebComponentWithDefault(eq(WebFilterRegistry.class), any(WebFilterRegistry.class)))
                .thenReturn(webFilterRegistry);

        // doFilter 模拟：新流程中 DefaultFilterChain 调用 handleAfterFilter
        try {
            doAnswer(invocation -> {
                WebServerHttpRequest req = invocation.getArgument(0);
                WebServerHttpResponse resp = invocation.getArgument(1);
                MappingResult mr = MappingResult.get(req);
                if (mr != null) {
                    handler.handleAfterFilter(req, resp, mr);
                }
                return null;
            }).when(webFilterRegistry).doFilter(any(), any());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        handler.initWithWebContext(webContext);
    }

    private WebServerHttpRequest createRequest() {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        RequestContext reqCtx = mock(RequestContext.class);
        when(req.getRequestContext()).thenReturn(reqCtx);
        // 模拟 fastAttributes 以支持 RequestAttribute 存取
        Map<RequestAttribute<?>, Object> fastAttrs = new HashMap<>();
        when(reqCtx.getAttribute(any(RequestAttribute.class))).thenAnswer(inv -> fastAttrs.get(inv.getArgument(0)));
        doAnswer(inv -> {
            fastAttrs.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(reqCtx).setAttribute(any(RequestAttribute.class), any());
        return req;
    }

    @Test
    void concurrentHandle_allThreadsComplete() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        PathMappingContext mappingContext = mock(PathMappingContext.class);
        MappingResult matched = MappingResult.matched(mappingContext);
        when(mappingRegistry.mapping(any())).thenAnswer(invocation -> {
            WebServerHttpRequest req = invocation.getArgument(0);
            MappingResult.set(req, matched);
            return matched;
        });
        when(corsRegistry.corsHandle(any(), any())).thenReturn(false);
        when(interceptorRegistry.preHandle(any(), any())).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(any(), any(), any())).thenReturn(new Object[0]);
        when(bizPoolRegistry.determinePool(any(), any())).thenReturn(null);

        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                try {
                    WebServerHttpRequest req = createRequest();
                    WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
                    handler.handle(req, resp);
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "并发请求未在 10 秒内结束");
        executor.shutdown();

        assertNull(firstError.get(), "No thread should throw an exception");
        assertEquals(threadCount, successCount.get(), "All threads should complete successfully");
        // 证明每个线程都真正走过路由匹配，而不是仅“没抛异常”
        verify(mappingRegistry, times(threadCount)).mapping(any());
    }

    @Test
    void concurrentHandle_withDifferentRoutes_isolationMaintained() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        Map<String, MappingResult> expectedByUri = new ConcurrentHashMap<>();

        when(mappingRegistry.mapping(any())).thenAnswer(invocation -> {
            WebServerHttpRequest req = invocation.getArgument(0);
            String uri = req.getUriStr();
            MappingResult matched = MappingResult.matched(mock(PathMappingContext.class));
            expectedByUri.put(uri, matched);
            MappingResult.set(req, matched);
            return matched;
        });
        when(corsRegistry.corsHandle(any(), any())).thenReturn(false);
        when(interceptorRegistry.preHandle(any(), any())).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(any(), any(), any())).thenReturn(new Object[0]);
        when(bizPoolRegistry.determinePool(any(), any())).thenReturn(null);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.execute(() -> {
                try {
                    WebServerHttpRequest req = createRequest();
                    when(req.getUriStr()).thenReturn("/thread-" + threadId);
                    WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
                    handler.handle(req, resp);
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "并发请求未在 10 秒内结束");
        executor.shutdown();

        assertNull(firstError.get(), "No thread should throw an exception");
        assertEquals(threadCount, successCount.get(), "All threads should complete successfully");
        assertEquals(threadCount, expectedByUri.size(), "每个线程的 URI 都应独立进入映射");
        verify(mappingRegistry, times(threadCount)).mapping(any());
    }

    @Test
    void concurrentHandle_notFound_allThreadsReceive404() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        MappingResult notFound = MappingResult.notFound();
        when(mappingRegistry.mapping(any())).thenAnswer(invocation -> {
            WebServerHttpRequest req = invocation.getArgument(0);
            MappingResult.set(req, notFound);
            return notFound;
        });

        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                try {
                    WebServerHttpRequest req = createRequest();
                    WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
                    handler.handle(req, resp);
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "并发请求未在 10 秒内结束");
        executor.shutdown();

        assertNull(firstError.get(), "请求处理线程不得抛异常: " + firstError.get());
        assertEquals(threadCount, successCount.get(), "All threads should complete");
        // 404 语义必须落到异常解析链：捕获交给 ExceptionRegistry 的异常并核对状态码
        ArgumentCaptor<Throwable> exceptionCaptor = ArgumentCaptor.forClass(Throwable.class);
        verify(exceptionRegistry, times(threadCount)).handle(exceptionCaptor.capture(), any(), any());
        for (Throwable ex : exceptionCaptor.getAllValues()) {
            ResponseStatusException statusEx = assertInstanceOf(ResponseStatusException.class, ex,
                    "未匹配应以 ResponseStatusException 承载状态码");
            assertEquals(HttpStatus.NOT_FOUND, statusEx.getStatusCode(), "所有线程都应得到 404");
        }
    }

    @Test
    void concurrentHandle_withExecutorPool_allThreadsComplete() throws Exception {
        ExecutorService bizPool = Executors.newCachedThreadPool();
        int threadCount = 8;
        ExecutorService testExecutor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        PathMappingContext mappingContext = mock(PathMappingContext.class);
        MappingResult matched = MappingResult.matched(mappingContext);
        when(mappingRegistry.mapping(any())).thenAnswer(invocation -> {
            WebServerHttpRequest req = invocation.getArgument(0);
            MappingResult.set(req, matched);
            return matched;
        });
        when(corsRegistry.corsHandle(any(), any())).thenReturn(false);
        when(interceptorRegistry.preHandle(any(), any())).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(any(), any(), any())).thenReturn(new Object[0]);
        when(bizPoolRegistry.determinePool(any(), any())).thenReturn(bizPool);

        for (int i = 0; i < threadCount; i++) {
            testExecutor.execute(() -> {
                try {
                    WebServerHttpRequest req = createRequest();
                    WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
                    handler.handle(req, resp);
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    firstError.compareAndSet(null, t);
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "并发请求未在 10 秒内结束");
        testExecutor.shutdown();
        bizPool.shutdown();

        assertNull(firstError.get(), "No thread should throw");
        assertEquals(threadCount, successCount.get(), "All threads should complete with executor offload");
        // 证明每个请求确实走了「交棒线程池」分支，而不是仅没抛异常
        verify(bizPoolRegistry, times(threadCount)).determinePool(any(), any());
    }
}
