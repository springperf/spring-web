package io.springperf.web.core.async;

import io.springperf.web.core.async.reactive.PublisherToDeferredResultAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Subscription;
import org.springframework.core.ReactiveAdapter;
import org.springframework.web.context.request.async.DeferredResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublisherToDeferredResultAdapterTest {

    @Mock
    ReactiveAdapter adapter;

    @Test
    void onSubscribe_requestsMaxValue() {
        DeferredResult<Object> result = new DeferredResult<>();
        PublisherToDeferredResultAdapter adapter = new PublisherToDeferredResultAdapter(result, this.adapter);
        Subscription subscription = mock(Subscription.class);

        adapter.onSubscribe(subscription);

        verify(subscription).request(Long.MAX_VALUE);
    }

    @Test
    void onSubscribe_setsTimeoutToCancel() throws Exception {
        DeferredResult<Object> result = new DeferredResult<>();
        PublisherToDeferredResultAdapter adapter = new PublisherToDeferredResultAdapter(result, this.adapter);
        Subscription subscription = mock(Subscription.class);
        adapter.onSubscribe(subscription);

        // Use reflection to get the timeout callback from DeferredResult
        java.lang.reflect.Field timeoutField = DeferredResult.class.getDeclaredField("timeoutCallback");
        timeoutField.setAccessible(true);
        Runnable timeoutHandler = (Runnable) timeoutField.get(result);
        assertNotNull(timeoutHandler);

        timeoutHandler.run();

        verify(subscription).cancel();
    }

    @Test
    void onNext_accumulatesValues() {
        DeferredResult<Object> result = new DeferredResult<>();
        PublisherToDeferredResultAdapter adapter = new PublisherToDeferredResultAdapter(result, this.adapter);
        Subscription subscription = mock(Subscription.class);
        adapter.onSubscribe(subscription);

        adapter.onNext("value1");
        adapter.onNext("value2");
    }

    @Test
    void onError_setsErrorResult() {
        DeferredResult<Object> result = new DeferredResult<>();
        PublisherToDeferredResultAdapter adapter = new PublisherToDeferredResultAdapter(result, this.adapter);
        Subscription subscription = mock(Subscription.class);
        adapter.onSubscribe(subscription);

        Throwable ex = new RuntimeException("test error");
        adapter.onError(ex);

        assertTrue(result.hasResult());
        assertSame(ex, result.getResult());
    }

    @Test
    void onComplete_singleValue_setsResult() {
        DeferredResult<Object> result = new DeferredResult<>();
        PublisherToDeferredResultAdapter adapter = new PublisherToDeferredResultAdapter(result, this.adapter);
        Subscription subscription = mock(Subscription.class);
        adapter.onSubscribe(subscription);

        adapter.onNext("single");
        adapter.onComplete();

        assertTrue(result.hasResult());
        assertEquals("single", result.getResult());
    }

    @Test
    void onComplete_multipleValues_setsResultList() {
        DeferredResult<Object> result = new DeferredResult<>();
        PublisherToDeferredResultAdapter adapter = new PublisherToDeferredResultAdapter(result, this.adapter);
        Subscription subscription = mock(Subscription.class);
        adapter.onSubscribe(subscription);

        adapter.onNext("a");
        adapter.onNext("b");
        adapter.onComplete();

        assertTrue(result.hasResult());
        assertTrue(result.getResult() instanceof java.util.List);
        java.util.List<Object> list = (java.util.List<Object>) result.getResult();
        assertEquals(2, list.size());
        assertEquals("a", list.get(0));
        assertEquals("b", list.get(1));
    }

    @Test
    void onComplete_empty_setsResultNull() {
        DeferredResult<Object> result = new DeferredResult<>();
        PublisherToDeferredResultAdapter adapter = new PublisherToDeferredResultAdapter(result, this.adapter);
        Subscription subscription = mock(Subscription.class);
        adapter.onSubscribe(subscription);

        adapter.onComplete();

        assertTrue(result.hasResult());
        assertNull(result.getResult());
    }

    @Test
    void onComplete_multiValueSource_singleValue_returnsList() {
        when(adapter.isMultiValue()).thenReturn(true);
        DeferredResult<Object> result = new DeferredResult<>();
        PublisherToDeferredResultAdapter pAdapter = new PublisherToDeferredResultAdapter(result, adapter);
        Subscription subscription = mock(Subscription.class);
        pAdapter.onSubscribe(subscription);

        pAdapter.onNext("single");
        pAdapter.onComplete();

        assertTrue(result.hasResult());
        assertTrue(result.getResult() instanceof java.util.List);
        java.util.List<Object> list = (java.util.List<Object>) result.getResult();
        assertEquals(1, list.size());
        assertEquals("single", list.get(0));
    }

    @Test
    void subscribe_delegatesToAdapter() {
        org.reactivestreams.Publisher<Object> publisher = mock(org.reactivestreams.Publisher.class);
        when(adapter.toPublisher(any())).thenReturn(publisher);

        PublisherToDeferredResultAdapter pAdapter = new PublisherToDeferredResultAdapter(new DeferredResult<>(), adapter);
        Object returnValue = new Object();

        pAdapter.subscribe(adapter, returnValue);

        verify(adapter).toPublisher(returnValue);
    }
}
