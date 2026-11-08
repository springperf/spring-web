package io.springperf.web.batch.invoker;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchOverflowException;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import io.springperf.web.batch.queue.DisruptorQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

    static class MultiArgRequest extends BatchRequest<String> {
        final String name;
        final int age;

        public MultiArgRequest(String name, int age) {
            this.name = name;
            this.age = age;
        }
    }

    static class ExplodingRequest extends BatchRequest<String> {
        public ExplodingRequest(String ignored) {
            throw new IllegalStateException("ctor boom");
        }
    }

    @Mock
    private DisruptorQueue queue;

    private BatchRequestMetaData meta(Class<?> requestType, Constructor<?> ctor) throws Exception {
        return new BatchRequestMetaData(Object.class.getDeclaredMethod("hashCode"), requestType, null, "test-queue",
                1024, BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK, ctor, 0, 4);
    }

    @Test
    void invoke_createsInstanceAndEnqueues() throws Exception {
        Constructor<?> ctor = TestRequest.class.getDeclaredConstructor(String.class);
        BatchInvoker invoker = new BatchInvoker(meta(TestRequest.class, ctor), queue);

        Object result = invoker.invoke(new Object[] { "hello" });

        assertThat(result).isInstanceOf(TestRequest.class);
        assertThat(((TestRequest) result).msg).isEqualTo("hello");
        verify(queue, times(1)).enqueue((BatchRequest<?>) result);
    }

    @Test
    void invoke_multiArgConstructor_passesArgumentsInOrder() throws Exception {
        Constructor<?> ctor = MultiArgRequest.class.getDeclaredConstructor(String.class, int.class);
        BatchInvoker invoker = new BatchInvoker(meta(MultiArgRequest.class, ctor), queue);

        Object result = invoker.invoke(new Object[] { "alice", 30 });

        assertThat(((MultiArgRequest) result).name).isEqualTo("alice");
        assertThat(((MultiArgRequest) result).age).isEqualTo(30);
        verify(queue).enqueue((BatchRequest<?>) result);
    }

    @Test
    void invoke_constructorFailure_propagates() throws Exception {
        Constructor<?> ctor = ExplodingRequest.class.getDeclaredConstructor(String.class);
        BatchInvoker invoker = new BatchInvoker(meta(ExplodingRequest.class, ctor), queue);

        assertThatThrownBy(() -> invoker.invoke(new Object[] { "x" })).isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
        verify(queue, times(0)).enqueue(any());
    }

    @Test
    void invoke_enqueueFailure_propagates() throws Exception {
        Constructor<?> ctor = TestRequest.class.getDeclaredConstructor(String.class);
        BatchInvoker invoker = new BatchInvoker(meta(TestRequest.class, ctor), queue);
        doThrow(new BatchOverflowException("test-queue")).when(queue).enqueue(any());

        assertThatThrownBy(() -> invoker.invoke(new Object[] { "hello" })).isInstanceOf(BatchOverflowException.class);
    }

    @Test
    void getHandleMethod() throws Exception {
        Constructor<?> ctor = TestRequest.class.getDeclaredConstructor(String.class);
        BatchInvoker invoker = new BatchInvoker(meta(TestRequest.class, ctor), queue);

        assertThat(invoker.getHandleMethod()).isEqualTo(Object.class.getDeclaredMethod("hashCode"));
    }

    @Test
    void getType() throws Exception {
        Constructor<?> ctor = TestRequest.class.getDeclaredConstructor(String.class);
        BatchInvoker invoker = new BatchInvoker(meta(TestRequest.class, ctor), queue);

        assertThat(invoker.getType()).isEqualTo("batch");
    }

    @Test
    void getMatchers_returnsEmptyList() throws Exception {
        Constructor<?> ctor = TestRequest.class.getDeclaredConstructor(String.class);
        BatchInvoker invoker = new BatchInvoker(meta(TestRequest.class, ctor), queue);

        assertThat(invoker.getMatchers()).isEmpty();
    }
}
