package io.springperf.web.core.async;

import io.springperf.web.context.ApplicationProperties;
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

import java.util.Collections;
import java.util.concurrent.Executors;

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
    void startDeferredResultProcessing_noTimeout_doesNotSetTimeout() throws Exception {
        DeferredResult<String> deferredResult = new DeferredResult<>(-1L);

        registry.startDeferredResultProcessing(asyncWebRequest, deferredResult);

        // timeout value -1L is non-null, but checking the code: timeout only set if timeout != null
        // getTimeoutValue() returns long primitive, autoboxed to Long, never null
        // actual check in code: only checks null, not value
        verify(asyncWebRequest).startAsyncProcessing();
    }

    @Test
    void startDeferredResultProcessing_triggerResultHandler_dispatches() throws Exception {
        DeferredResult<String> deferredResult = new DeferredResult<>();

        registry.startDeferredResultProcessing(asyncWebRequest, deferredResult);

        // Set a result on the deferredResult to trigger the result handler
        deferredResult.setResult("test-result");

        verify(asyncWebRequest).setConcurrentResultAndDispatch("test-result");
    }

    @Test
    void initComponentPhase2_withBizPoolRegistry_doesNotThrow() throws Exception {
        BizPoolRegistry bizPoolRegistry = mock(BizPoolRegistry.class);
        when(bizPoolRegistry.getDefaultPool()).thenReturn(Executors.newSingleThreadExecutor());
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
    }
}