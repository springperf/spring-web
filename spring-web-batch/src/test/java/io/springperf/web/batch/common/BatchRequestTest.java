package io.springperf.web.batch.common;

import org.junit.jupiter.api.Test;

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
    }

    @Test
    void setResultMarksCompleted() {
        TestRequest req = new TestRequest();
        boolean result = req.setResult("ok");
        assertThat(result).isTrue();
        assertThat(req.isCompleted()).isTrue();
    }

    @Test
    void setErrorMarksCompleted() {
        TestRequest req = new TestRequest();
        req.setError(new RuntimeException("fail"));
        assertThat(req.isCompleted()).isTrue();
    }

    @Test
    void setResultIsIdempotent() {
        TestRequest req = new TestRequest();
        assertThat(req.setResult("first")).isTrue();
        assertThat(req.setResult("second")).isFalse();
        assertThat(req.isCompleted()).isTrue();
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
        req.setError(new RuntimeException("fail"));
        boolean result = req.setResult("ok");
        assertThat(result).isFalse();
        assertThat(req.isCompleted()).isTrue();
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

    /** 覆盖 {@code protected BatchRequest(Long)}：子类可用自定义超时，且守卫语义不变。 */
    private static class CustomTimeoutRequest extends BatchRequest<String> {
        CustomTimeoutRequest(long timeout) {
            super(timeout);
        }
    }

    @Test
    void customTimeoutConstructor_worksAndGuardsFirstWins() {
        CustomTimeoutRequest req = new CustomTimeoutRequest(12345L);
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
        }
    }
}
