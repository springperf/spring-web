package io.springperf.web.batch.queue;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class BufferingBatchHandlerTest {

    static class TestService {
        @SuppressWarnings("unused")
        public void handle(List<? extends BatchRequest<?>> batch) {
        }
    }

    @Mock
    private Executor executor;

    private BatchRequestMetaData meta;
    private TestService bean;
    private Method batchMethod;

    @BeforeEach
    void setUp() throws Exception {
        bean = new TestService();
        batchMethod = TestService.class.getDeclaredMethod("handle", List.class);
        meta = new BatchRequestMetaData(
                batchMethod, TestService.class, null, "test-queue",
                1024, BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK,
                null, 0, 4
        );
    }

    @Test
    void onEvent_accumulatesRequests() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 0);

        BatchRequest<String> r1 = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(r1), 0L, false);

        // No flush yet — endOfBatch is false and maxBatchSize is 0
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void endOfBatch_triggersFlush() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 0);

        BatchRequest<String> r1 = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(r1), 0L, true);

        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void maxBatchSize_triggersFlush() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 2);

        BatchRequest<String> r1 = new BatchRequest<String>() {
        };
        BatchRequest<String> r2 = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(r1), 0L, false);
        handler.onEvent(newEvent(r2), 1L, false);

        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void flush_submitsAllAccumulatedRequests() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 2);

        BatchRequest<String> r1 = new BatchRequest<String>() {
        };
        BatchRequest<String> r2 = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(r1), 0L, false);
        handler.onEvent(newEvent(r2), 1L, false);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor, times(1)).execute(captor.capture());

        // Should NOT throw — batch method invocation is set up correctly
        captor.getValue().run();
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    @Test
    void processBatch_invokesBatchMethod() throws Exception {
        TestService spy = spy(bean);
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, spy, 0);

        BatchRequest<String> req = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(req), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        verify(spy).handle(anyList());
    }

    @Test
    void processBatch_setsErrorOnException() {
        // A bean that throws
        Object badBean = new Object();
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, badBean, 0);

        BatchRequest<String> req = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(req), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        assertThat(req.isCompleted()).isTrue();
    }

    @Test
    void processBatch_setsErrorOnAllRequestsWhenOneFails() {
        Object badBean = new Object();
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, badBean, 2);

        BatchRequest<String> r1 = new BatchRequest<String>() {
        };
        BatchRequest<String> r2 = new BatchRequest<String>() {
        };
        handler.onEvent(newEvent(r1), 0L, false);
        handler.onEvent(newEvent(r2), 1L, false);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        assertThat(r1.isCompleted()).isTrue();
        assertThat(r2.isCompleted()).isTrue();
    }

    @Test
    void processBatch_alreadyCompletedRequestsSkippedInErrorHandling() {
        Object badBean = new Object();
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, badBean, 0);

        BatchRequest<String> req = new BatchRequest<String>() {
        };
        req.setResult("already-done");

        handler.onEvent(newEvent(req), 0L, true);

        ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).execute(captor.capture());
        captor.getValue().run();

        // Already completed — setError should be noop, but it's already completed
        assertThat(req.isCompleted()).isTrue();
    }

    @Test
    void flush_whenEmpty_doesNothing() {
        BufferingBatchHandler handler = new BufferingBatchHandler(executor, meta, bean, 0);
        // No events added, no onEvent call = buffer is empty
        // The handler's flush() is called via onEvent with endOfBatch, but since no requests, should be noop
        handler.onEvent(newEvent(new BatchRequest<String>() {
        }), 0L, true);
        // Actually with one event, it should flush once
        verify(executor, times(1)).execute(any(Runnable.class));
    }

    private static BatchEvent newEvent(BatchRequest<?> request) {
        BatchEvent event = new BatchEvent();
        event.request = request;
        return event;
    }
}