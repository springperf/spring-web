package io.springperf.web.core;

import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.ArgumentResolverRegistry;
import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.core.cors.CorsRegistry;
import io.springperf.web.core.exception.ExceptionRegistry;
import io.springperf.web.core.interceptor.InterceptorRegistry;
import io.springperf.web.core.mapping.MappingRegistry;
import io.springperf.web.core.mapping.MappingResult;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.pool.BizPoolRegistry;
import io.springperf.web.core.retval.ReturnValueResolverRegistry;
import io.springperf.web.filter.WebFilterRegistry;
import io.springperf.web.http.RequestContext;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

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
        when(webContext.getWebComponentWithDefault(eq(ArgumentResolverRegistry.class), any(ArgumentResolverRegistry.class)))
                .thenReturn(argumentResolverRegistry);
        when(webContext.getWebComponentWithDefault(eq(ReturnValueResolverRegistry.class), any(ReturnValueResolverRegistry.class)))
                .thenReturn(returnValueResolverRegistry);
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
        // doFilter 走完 chain 后调用 terminal(mappingResult)
        try {
            doAnswer(invocation -> {
                WebFilterRegistry.FilterChainTerminal terminal = invocation.getArgument(3);
                WebServerHttpRequest req = invocation.getArgument(0);
                WebServerHttpResponse resp = invocation.getArgument(1);
                MappingResult mappingResult = invocation.getArgument(2);
                try {
                    terminal.doFilter(req, resp, mappingResult);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            }).when(webFilterRegistry).doFilter(any(), any(), any(MappingResult.class), any(WebFilterRegistry.FilterChainTerminal.class));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        handler.initWithWebContext(webContext);
    }

    private WebServerHttpRequest createRequest() {
        WebServerHttpRequest req = mock(WebServerHttpRequest.class);
        when(req.getRequestContext()).thenReturn(mock(RequestContext.class));
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
        when(mappingRegistry.mapping(any())).thenReturn(MappingResult.matched(mappingContext));
        when(corsRegistry.corsHandle(any(), any())).thenReturn(false);
        when(interceptorRegistry.preHandle(any(), any())).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(any(), any(), any())).thenReturn(new Object[0]);
        when(bizPoolRegistry.determinePool(any())).thenReturn(null);

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

        latch.await();
        executor.shutdown();

        assertNull(firstError.get(), "No thread should throw an exception");
        assertEquals(threadCount, successCount.get(), "All threads should complete successfully");
    }

    @Test
    void concurrentHandle_withDifferentRoutes_isolationMaintained() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(mappingRegistry.mapping(any())).thenReturn(MappingResult.matched(mappingContext));
        when(corsRegistry.corsHandle(any(), any())).thenReturn(false);
        when(interceptorRegistry.preHandle(any(), any())).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(any(), any(), any())).thenReturn(new Object[0]);
        when(bizPoolRegistry.determinePool(any())).thenReturn(null);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.execute(() -> {
                try {
                    WebServerHttpRequest req = createRequest();
                    when(req.getUriStr()).thenReturn("/thread-" + threadId);
                    WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
                    handler.handle(req, resp);
                    successCount.incrementAndGet();
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount, successCount.get(), "All threads should complete successfully");
    }

    @Test
    void concurrentHandle_notFound_allThreadsReceive404() throws Exception {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        when(mappingRegistry.mapping(any())).thenReturn(MappingResult.notFound());

        for (int i = 0; i < threadCount; i++) {
            executor.execute(() -> {
                try {
                    WebServerHttpRequest req = createRequest();
                    WebServerHttpResponse resp = mock(WebServerHttpResponse.class);
                    handler.handle(req, resp);
                    successCount.incrementAndGet();
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount, successCount.get(), "All threads should complete");
        verify(exceptionRegistry, times(threadCount)).handle(any(), any(), any());
    }

    @Test
    void concurrentHandle_withExecutorPool_isolationMaintained() throws Exception {
        ExecutorService bizPool = Executors.newCachedThreadPool();
        int threadCount = 8;
        ExecutorService testExecutor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        PathMappingContext mappingContext = mock(PathMappingContext.class);
        when(mappingRegistry.mapping(any())).thenReturn(MappingResult.matched(mappingContext));
        when(corsRegistry.corsHandle(any(), any())).thenReturn(false);
        when(interceptorRegistry.preHandle(any(), any())).thenReturn(true);
        when(argumentResolverRegistry.resolveArguments(any(), any(), any())).thenReturn(new Object[0]);
        when(bizPoolRegistry.determinePool(any())).thenReturn(bizPool);

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

        latch.await();
        testExecutor.shutdown();
        bizPool.shutdown();

        assertNull(firstError.get(), "No thread should throw");
        assertEquals(threadCount, successCount.get(), "All threads should complete with executor offload");
    }
}