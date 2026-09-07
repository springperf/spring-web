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
}
