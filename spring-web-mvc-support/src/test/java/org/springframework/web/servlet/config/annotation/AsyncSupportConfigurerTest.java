package org.springframework.web.servlet.config.annotation;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;

import static org.junit.jupiter.api.Assertions.*;

class AsyncSupportConfigurerTest {

    private final AsyncSupportConfigurer configurer = new AsyncSupportConfigurer();

    @Test
    void setDefaultTimeout_storesValue() {
        configurer.setDefaultTimeout(5000);
        assertEquals(5000L, configurer.getTimeout().longValue());
    }

    @Test
    void setDefaultTimeout_returnsSelf() {
        assertSame(configurer, configurer.setDefaultTimeout(5000));
    }

    @Test
    void timeout_notSet_returnsNull() {
        assertNull(configurer.getTimeout());
    }

    @Test
    void setTaskExecutor_storesExecutor() {
        AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);
        configurer.setTaskExecutor(executor);
        assertSame(executor, configurer.getTaskExecutor());
    }

    @Test
    void setTaskExecutor_returnsSelf() {
        assertSame(configurer, configurer.setTaskExecutor(mock(AsyncTaskExecutor.class)));
    }

    @Test
    void taskExecutor_notSet_returnsNull() {
        assertNull(configurer.getTaskExecutor());
    }

    @Test
    void registerCallableInterceptors_storesInterceptors() {
        CallableProcessingInterceptor interceptor = mock(CallableProcessingInterceptor.class);
        configurer.registerCallableInterceptors(interceptor);
        assertEquals(1, configurer.getCallableInterceptors().size());
        assertTrue(configurer.getCallableInterceptors().contains(interceptor));
    }

    @Test
    void registerCallableInterceptors_multiple_storesAll() {
        CallableProcessingInterceptor i1 = mock(CallableProcessingInterceptor.class);
        CallableProcessingInterceptor i2 = mock(CallableProcessingInterceptor.class);
        configurer.registerCallableInterceptors(i1, i2);
        assertEquals(2, configurer.getCallableInterceptors().size());
    }

    @Test
    void registerCallableInterceptors_returnsSelf() {
        assertSame(configurer, configurer.registerCallableInterceptors(mock(CallableProcessingInterceptor.class)));
    }

    @Test
    void registerDeferredResultInterceptors_storesInterceptors() {
        DeferredResultProcessingInterceptor interceptor = mock(DeferredResultProcessingInterceptor.class);
        configurer.registerDeferredResultInterceptors(interceptor);
        assertEquals(1, configurer.getDeferredResultInterceptors().size());
        assertTrue(configurer.getDeferredResultInterceptors().contains(interceptor));
    }

    @Test
    void registerDeferredResultInterceptors_multiple_storesAll() {
        DeferredResultProcessingInterceptor i1 = mock(DeferredResultProcessingInterceptor.class);
        DeferredResultProcessingInterceptor i2 = mock(DeferredResultProcessingInterceptor.class);
        configurer.registerDeferredResultInterceptors(i1, i2);
        assertEquals(2, configurer.getDeferredResultInterceptors().size());
    }

    @Test
    void registerDeferredResultInterceptors_returnsSelf() {
        assertSame(configurer, configurer.registerDeferredResultInterceptors(mock(DeferredResultProcessingInterceptor.class)));
    }

    @Test
    void callableInterceptors_notSet_returnsEmptyList() {
        assertTrue(configurer.getCallableInterceptors().isEmpty());
    }

    @Test
    void deferredResultInterceptors_notSet_returnsEmptyList() {
        assertTrue(configurer.getDeferredResultInterceptors().isEmpty());
    }

    private static <T> T mock(Class<T> clazz) {
        return org.mockito.Mockito.mock(clazz);
    }
}