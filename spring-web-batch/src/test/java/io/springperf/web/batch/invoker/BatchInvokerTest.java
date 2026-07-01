package io.springperf.web.batch.invoker;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import io.springperf.web.batch.queue.DisruptorQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BatchInvokerTest {

    static class TestRequest extends BatchRequest<String> {
        final String msg;

        public TestRequest(String msg) {
            this.msg = msg;
        }
    }

    @Mock
    private DisruptorQueue queue;

    @Test
    void invoke_createsInstanceAndEnqueues() throws Exception {
        Constructor<?> ctor = TestRequest.class.getDeclaredConstructor(String.class);
        BatchRequestMetaData meta = new BatchRequestMetaData(
                Object.class.getDeclaredMethod("hashCode"),
                TestRequest.class, null, "test-queue",
                1024, BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK,
                ctor, 0, 4
        );

        BatchInvoker invoker = new BatchInvoker(meta, queue);
        Object result = invoker.invoke(new Object[]{"hello"});

        assertThat(result).isInstanceOf(TestRequest.class);
        assertThat(((TestRequest) result).msg).isEqualTo("hello");
        verify(queue, times(1)).enqueue((BatchRequest<?>) result);
    }

    @Test
    void getHandleMethod() throws Exception {
        Constructor<?> ctor = TestRequest.class.getDeclaredConstructor(String.class);
        BatchRequestMetaData meta = new BatchRequestMetaData(
                Object.class.getDeclaredMethod("hashCode"),
                TestRequest.class, null, "test-queue",
                1024, BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK,
                ctor, 0, 4
        );

        BatchInvoker invoker = new BatchInvoker(meta, queue);
        assertThat(invoker.getHandleMethod()).isEqualTo(Object.class.getDeclaredMethod("hashCode"));
    }

    @Test
    void getType() throws Exception {
        Constructor<?> ctor = TestRequest.class.getDeclaredConstructor(String.class);
        BatchRequestMetaData meta = new BatchRequestMetaData(
                Object.class.getDeclaredMethod("hashCode"),
                TestRequest.class, null, "test-queue",
                1024, BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK,
                ctor, 0, 4
        );

        BatchInvoker invoker = new BatchInvoker(meta, queue);
        assertThat(invoker.getType()).isEqualTo("batch");
    }

    @Test
    void getMatchers_returnsEmptyList() throws Exception {
        Constructor<?> ctor = TestRequest.class.getDeclaredConstructor(String.class);
        BatchRequestMetaData meta = new BatchRequestMetaData(
                Object.class.getDeclaredMethod("hashCode"),
                TestRequest.class, null, "test-queue",
                1024, BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK,
                ctor, 0, 4
        );

        BatchInvoker invoker = new BatchInvoker(meta, queue);
        assertThat(invoker.getMatchers()).isEmpty();
    }
}