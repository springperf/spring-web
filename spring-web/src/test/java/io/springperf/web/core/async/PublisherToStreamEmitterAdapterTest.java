package io.springperf.web.core.async;

import io.springperf.web.core.async.reactive.PublisherToStreamEmitterAdapter;
import io.springperf.web.core.async.reactive.ReactiveConfig;
import io.springperf.web.core.async.stream.SseEmitter;
import io.springperf.web.core.async.stream.StreamSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.reactivestreams.Subscription;
import org.springframework.core.ReactiveAdapter;

import java.io.IOException;
import java.util.function.Consumer;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PublisherToStreamEmitterAdapterTest {

    @Mock
    StreamSender sender;

    @Mock
    ReactiveAdapter adapter;

    ReactiveConfig config;
    SseEmitter emitter;
    PublisherToStreamEmitterAdapter streamAdapter;

    @BeforeEach
    void setUp() {
        config = new ReactiveConfig(10, 3, -1);
        emitter = spy(new SseEmitter());
        streamAdapter = new PublisherToStreamEmitterAdapter(emitter, sender, config);
    }

    @Test
    void onSubscribe_bindsEmitterLifecycle() {
        Subscription subscription = mock(Subscription.class);
        streamAdapter.onSubscribe(subscription);

        verify(subscription).request(10);
    }

    @Test
    void onSubscribe_timeoutTriggersCancel() {
        Subscription subscription = mock(Subscription.class);
        streamAdapter.onSubscribe(subscription);

        ArgumentCaptor<Runnable> timeoutCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(emitter).onTimeout(timeoutCaptor.capture());

        timeoutCaptor.getValue().run();

        verify(subscription).cancel();
    }

    @Test
    void onSubscribe_writeCallbackWithNull_triggersTryRequest() {
        Subscription subscription = mock(Subscription.class);
        streamAdapter.onSubscribe(subscription);

        ArgumentCaptor<Consumer<Throwable>> writeCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onWriteCallback(writeCaptor.capture());

        writeCaptor.getValue().accept(null);

        verify(subscription, times(2)).request(anyLong());
    }

    @Test
    void onSubscribe_writeCallbackWithError_triggersTryCancel() {
        Subscription subscription = mock(Subscription.class);
        streamAdapter.onSubscribe(subscription);

        ArgumentCaptor<Consumer<Throwable>> writeCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onWriteCallback(writeCaptor.capture());

        RuntimeException ex = new RuntimeException("write error");
        writeCaptor.getValue().accept(ex);

        verify(subscription).cancel();
    }

    @Test
    void onNext_sendsToSender() throws Exception {
        streamAdapter.onSubscribe(mock(Subscription.class));

        streamAdapter.onNext("data");

        verify(sender).send("data");
    }

    @Test
    void onNext_sendError_triggersTryCancel() throws Exception {
        Subscription subscription = mock(Subscription.class);
        streamAdapter.onSubscribe(subscription);
        doThrow(new IOException("send failed")).when(sender).send(any());

        streamAdapter.onNext("data");

        verify(subscription).cancel();
    }

    @Test
    void onError_completesWithError() {
        streamAdapter.onSubscribe(mock(Subscription.class));

        RuntimeException ex = new RuntimeException("test error");
        streamAdapter.onError(ex);

        verify(emitter).completeWithError(ex);
    }

    @Test
    void onError_afterTermination_isNoOp() {
        streamAdapter.onSubscribe(mock(Subscription.class));

        streamAdapter.onComplete(); // terminates
        streamAdapter.onError(new RuntimeException("late error"));

        verify(emitter, times(1)).complete(); // only the first one
        verify(emitter, never()).completeWithError(any());
    }

    @Test
    void onComplete_emitterCompletes() {
        streamAdapter.onSubscribe(mock(Subscription.class));

        streamAdapter.onComplete();

        verify(emitter).complete();
    }

    @Test
    void onComplete_afterTermination_isNoOp() {
        streamAdapter.onSubscribe(mock(Subscription.class));

        streamAdapter.onComplete();
        streamAdapter.onComplete();

        verify(emitter, times(1)).complete();
    }

    @Test
    void tryRequest_whenQueueLow_requestsMore() {
        Subscription subscription = mock(Subscription.class);
        streamAdapter.onSubscribe(subscription);

        when(sender.queueSize()).thenReturn(1);

        // trigger tryRequest through write callback
        ArgumentCaptor<Consumer<Throwable>> writeCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onWriteCallback(writeCaptor.capture());
        writeCaptor.getValue().accept(null);

        // 1 (queueSize) < 3 (lowWaterMark), so request additional (10 - 1) = 9
        verify(subscription).request(9);
    }

    @Test
    void tryRequest_whenQueueAboveLowWaterMark_doesNotRequest() {
        Subscription subscription = mock(Subscription.class);
        streamAdapter.onSubscribe(subscription);

        when(sender.queueSize()).thenReturn(5);

        // trigger tryRequest
        ArgumentCaptor<Consumer<Throwable>> writeCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onWriteCallback(writeCaptor.capture());
        writeCaptor.getValue().accept(null);

        // queueSize=5 >= lowWaterMark=3, should NOT request more
        verify(subscription, times(1)).request(10); // only initial request
    }

    @Test
    void tryRequest_afterTermination_isNoOp() {
        Subscription subscription = mock(Subscription.class);
        streamAdapter.onSubscribe(subscription);

        streamAdapter.onComplete();

        // Try to trigger tryRequest - should be no-op
        ArgumentCaptor<Consumer<Throwable>> writeCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(emitter).onWriteCallback(writeCaptor.capture());
        writeCaptor.getValue().accept(null);

        // Only the initial request was made
        verify(subscription, times(1)).request(anyLong());
    }

    @Test
    void subscribe_delegatesToAdapter() {
        org.reactivestreams.Publisher<Object> publisher = mock(org.reactivestreams.Publisher.class);
        when(adapter.toPublisher(any())).thenReturn(publisher);

        Object returnValue = new Object();
        streamAdapter.subscribe(adapter, returnValue);

        verify(adapter).toPublisher(returnValue);
    }
}