package io.springperf.web.batch.common;

import org.junit.jupiter.api.Test;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.context.request.async.DeferredResult;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class BatchRequestTest {

    private static class TestRequest extends BatchRequest<String> {
        TestRequest() {
        }
    }

    @Test
    void notCompletedInitially() {
        TestRequest req = new TestRequest();
        assertThat(req.isCompleted()).isFalse();
        assertThat(req.hasResult()).isFalse();
    }

    @Test
    void setResultMarksCompleted() {
        TestRequest req = new TestRequest();
        boolean result = req.setResult("ok");
        assertThat(result).isTrue();
        assertThat(req.isCompleted()).isTrue();
        assertThat(req.hasResult()).isTrue();
        assertThat(req.getResult()).isEqualTo("ok");
    }

    @Test
    void setErrorMarksCompleted() {
        TestRequest req = new TestRequest();
        RuntimeException ex = new RuntimeException("fail");

        req.setError(ex);

        assertThat(req.isCompleted()).isTrue();
        assertThat(req.hasResult()).isTrue();
        assertThat(req.getResult()).isSameAs(ex);
    }

    @Test
    void setResultIsIdempotent() {
        TestRequest req = new TestRequest();
        assertThat(req.setResult("first")).isTrue();
        assertThat(req.setResult("second")).isFalse();
        assertThat(req.isCompleted()).isTrue();
        // 幂等的关键语义：首个结果必须保留，后续写入不得覆盖
        assertThat(req.getResult()).isEqualTo("first");
    }

    @Test
    void setErrorAfterSetResultIsNoop() {
        TestRequest req = new TestRequest();
        assertThat(req.setResult("ok")).isTrue();
        req.setError(new RuntimeException("later"));
        // setError 不应覆盖已有的成功结果：结果仍为 "ok"（noop 语义）
        assertThat(req.isCompleted()).isTrue();
        assertThat(req.getResult()).isEqualTo("ok");
    }

    @Test
    void setResultAfterSetErrorIsNoop() {
        TestRequest req = new TestRequest();
        RuntimeException ex = new RuntimeException("fail");
        req.setError(ex);

        boolean result = req.setResult("ok");

        assertThat(result).isFalse();
        assertThat(req.isCompleted()).isTrue();
        // 首个错误必须保留，不得被后续 setResult 覆盖
        assertThat(req.getResult()).isSameAs(ex);
    }

    @Test
    void setErrorIsIdempotent() {
        TestRequest req = new TestRequest();
        req.setError(new RuntimeException("first"));
        // 第二次 setError 不抛异常、状态仍为 completed，且首次错误被保留
        req.setError(new RuntimeException("second"));
        assertThat(req.isCompleted()).isTrue();
        assertThat(((Throwable) req.getResult()).getMessage()).isEqualTo("first");
    }

    // ------------------------------------------------------------------
    // 超时 / 迟到批次的竞态：两条写入路径必须“先到先得”
    // ------------------------------------------------------------------

    @Test
    void timeoutWins_lateResultCannotOverwriteTimeoutError() {
        TestRequest req = new TestRequest();
        AsyncRequestTimeoutException timeout = new AsyncRequestTimeoutException();

        // 容器超时先到（框架通过 setErrorResult 写入）
        assertThat(req.setErrorResult(timeout)).isTrue();
        assertThat(req.isCompleted()).isTrue();

        // 批次迟到：不得覆盖已返回给客户端的超时错误
        assertThat(req.setResult("late")).isFalse();
        assertThat(req.getResult()).isSameAs(timeout);
    }

    /**
     * 容器只置 expired 标志、尚未写入结果的窗口（Spring 内部状态，用反射模拟）。
     * <p>
     * 此时父类 {@code setResult} 会拒绝写入；如果 {@code BatchRequest} 先置 completed 再写入， 就会出现「父类无结果、内部却标记已完成」的不一致——后续
     * {@code failUncompleted} 会跳过该请求， 请求静默失联。
     * </p>
     */
    @Test
    void expiredWithoutWrite_lateResultCannotMarkCompleted() throws Exception {
        TestRequest req = new TestRequest();
        Field expired = DeferredResult.class.getDeclaredField("expired");
        expired.setAccessible(true);
        expired.setBoolean(req, true);

        assertThat(req.setResult("late")).isFalse();
        assertThat(req.isCompleted()).as("父类拒绝后不得把内部状态标记为已完成").isFalse();
        assertThat(req.hasResult()).isFalse();
    }

    @Test
    void timeoutWins_lateBatchErrorCannotOverwriteTimeoutError() {
        TestRequest req = new TestRequest();
        AsyncRequestTimeoutException timeout = new AsyncRequestTimeoutException();
        assertThat(req.setErrorResult(timeout)).isTrue();

        // 批次异常处理（handleBatchError / failUncompleted）迟到，同样不得覆盖
        req.setError(new IllegalStateException("late batch failure"));

        assertThat(req.isCompleted()).isTrue();
        assertThat(req.getResult()).isSameAs(timeout);
    }

    @Test
    void batchResultWins_lateTimeoutCannotOverwriteResult() {
        TestRequest req = new TestRequest();
        assertThat(req.setResult("ok")).isTrue();

        // 超时定时器在结果提交后触发：必须被拒绝，且不能把成功结果改成 503
        assertThat(req.setErrorResult(new AsyncRequestTimeoutException())).isFalse();
        assertThat(req.getResult()).isEqualTo("ok");
    }

    @Test
    void concurrentResultAndTimeout_exactlyOneWins() throws Exception {
        // 并发竞争守卫：200 轮，setResult 与超时回调同时起跑，只允许一个成功
        for (int round = 0; round < 200; round++) {
            TestRequest req = new TestRequest();
            java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(2);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
            java.util.concurrent.Future<Boolean> result = pool.submit(() -> {
                barrier.await();
                return req.setResult("ok");
            });
            java.util.concurrent.Future<Boolean> timeout = pool.submit(() -> {
                barrier.await();
                return req.setErrorResult(new AsyncRequestTimeoutException());
            });

            boolean resultWon = result.get();
            boolean timeoutWon = timeout.get();
            pool.shutdown();

            assertThat(resultWon ^ timeoutWon).as("结果与超时只能有一个生效: round=" + round).isTrue();
            if (resultWon) {
                assertThat(req.getResult()).isEqualTo("ok");
            } else {
                assertThat(req.getResult()).isInstanceOf(AsyncRequestTimeoutException.class);
            }
        }
    }

    /** 覆盖 {@code protected BatchRequest(Long)}：子类可用自定义超时，且守卫语义不变。 */
    private static class CustomTimeoutRequest extends BatchRequest<String> {
        CustomTimeoutRequest(long timeout) {
            super(timeout);
        }
    }

    @Test
    void customTimeoutConstructor_worksAndGuardsFirstWins() throws Exception {
        CustomTimeoutRequest req = new CustomTimeoutRequest(12345L);
        Field timeoutValue = DeferredResult.class.getDeclaredField("timeoutValue");
        timeoutValue.setAccessible(true);
        assertThat(timeoutValue.get(req)).isEqualTo(12345L);
        assertThat(req.isCompleted()).isFalse();
        assertThat(req.setResult("ok")).isTrue();
        assertThat(req.setResult("again")).isFalse();
        assertThat(req.getResult()).isEqualTo("ok");
    }

    @Test
    void concurrentSetResult_onlyOneWins() throws Exception {
        // compareAndSet 守卫的回归：N 个线程并发完成同一请求，只允许一个 setResult 生效
        for (int round = 0; round < 200; round++) {
            TestRequest req = new TestRequest();
            int threads = 8;
            java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threads);
            java.util.List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return req.setResult("x");
                }));
            }
            int wins = 0;
            for (java.util.concurrent.Future<Boolean> f : futures) {
                if (f.get()) {
                    wins++;
                }
            }
            pool.shutdown();
            assertThat(wins).isEqualTo(1);
            assertThat(req.getResult()).isEqualTo("x");
        }
    }
}
