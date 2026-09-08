package io.springperf.web.http;

import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 验证 {@link BaseWebServerHttpResponse#addWriteRespEventListener} 的多监听器复合广播逻辑
 * （{@code CompositeWriteRespEventListener}）：多个监听器共存时各回调都到达。
 */
class CompositeWriteRespEventListenerTest {

    /** 最小可测响应子类（复用基类的监听器组合逻辑） */
    static class TestResponse extends BaseWebServerHttpResponse {
        TestResponse(WebContext webContext) {
            super(webContext, true, new HttpHeaders());
        }

        @Override
        void runOnEventLoop(Runnable task) {
            task.run();
        }

        @Override
        ScheduledFuture scheduleOnEventLoop(Runnable task, long delay, TimeUnit unit) {
            return null;
        }

        @Override
        public void flush(boolean chunked) throws java.io.IOException {
            // 测试无需真正 flush
        }

        @Override
        public void writeBytes(byte[] data) {
            // 测试无需真正写
        }

        @Override
        public void writeFile(java.io.File file) {
            // 测试无需真正写文件
        }

        @Override
        public void writeStream(java.io.InputStream input) {
            // 测试无需真正流式写
        }
    }

    @Test
    void addFirstListener_direct() {
        TestResponse response = new TestResponse(mock(WebContext.class));
        WriteRespEventListener l1 = mock(WriteRespEventListener.class);
        response.addWriteRespEventListener(l1);
        response.writeRespEventListener.completeSuccessCallback();
        org.mockito.Mockito.verify(l1).completeSuccessCallback();
    }

    @Test
    void addSecondListener_composites_broadcastsAllCallbacks() {
        TestResponse response = new TestResponse(mock(WebContext.class));
        WriteRespEventListener l1 = mock(WriteRespEventListener.class);
        WriteRespEventListener l2 = mock(WriteRespEventListener.class);
        WriteRespEventListener l3 = mock(WriteRespEventListener.class);
        response.addWriteRespEventListener(l1);
        response.addWriteRespEventListener(l2);
        response.addWriteRespEventListener(l3);

        response.writeRespEventListener.completeSuccessCallback();
        org.mockito.Mockito.verify(l1).completeSuccessCallback();
        org.mockito.Mockito.verify(l2).completeSuccessCallback();
        org.mockito.Mockito.verify(l3).completeSuccessCallback();
    }

    @Test
    void composite_broadcastsCompleteError() {
        TestResponse response = new TestResponse(mock(WebContext.class));
        WriteRespEventListener l1 = mock(WriteRespEventListener.class);
        WriteRespEventListener l2 = mock(WriteRespEventListener.class);
        response.addWriteRespEventListener(l1);
        response.addWriteRespEventListener(l2);

        Throwable t = new RuntimeException("boom");
        response.writeRespEventListener.completeErrorCallback(t);
        org.mockito.Mockito.verify(l1).completeErrorCallback(t);
        org.mockito.Mockito.verify(l2).completeErrorCallback(t);
    }

    @Test
    void composite_broadcastsStreamCallbacks() {
        TestResponse response = new TestResponse(mock(WebContext.class));
        WriteRespEventListener l1 = mock(WriteRespEventListener.class);
        WriteRespEventListener l2 = mock(WriteRespEventListener.class);
        response.addWriteRespEventListener(l1);
        response.addWriteRespEventListener(l2);

        response.writeRespEventListener.writeStreamSuccessCallback();
        org.mockito.Mockito.verify(l1).writeStreamSuccessCallback();
        org.mockito.Mockito.verify(l2).writeStreamSuccessCallback();

        Throwable t = new RuntimeException("stream-fail");
        response.writeRespEventListener.writeStreamErrorCallback(t);
        org.mockito.Mockito.verify(l1).writeStreamErrorCallback(t);
        org.mockito.Mockito.verify(l2).writeStreamErrorCallback(t);
    }

    @Test
    void setWriteRespEventListener_replacesExisting() {
        TestResponse response = new TestResponse(mock(WebContext.class));
        WriteRespEventListener l1 = mock(WriteRespEventListener.class);
        WriteRespEventListener l2 = mock(WriteRespEventListener.class);
        response.addWriteRespEventListener(l1);
        response.setWriteRespEventListener(l2);
        response.writeRespEventListener.completeSuccessCallback();
        org.mockito.Mockito.verify(l2).completeSuccessCallback();
        org.mockito.Mockito.verify(l1, org.mockito.Mockito.never()).completeSuccessCallback();
    }

    @Test
    void resetBuffer_returnsWhetherHadData() {
        TestResponse response = new TestResponse(mock(WebContext.class));
        assertFalse(response.resetBuffer(), "空 buffer reset 应返回 false");
        try {
            response.getBody().write(new byte[]{1, 2, 3});
        } catch (java.io.IOException ignored) {
        }
        assertTrue(response.resetBuffer(), "有数据 buffer reset 应返回 true");
        assertEquals(0, response.getBufferSize());
    }

    @Test
    void setStatusCode_acceptsHttpStatus() {
        TestResponse response = new TestResponse(mock(WebContext.class));
        response.setStatusCode(HttpStatus.CREATED);
        assertEquals(HttpStatus.CREATED, response.getStatus());
        response.setStatusCode(HttpStatus.ACCEPTED);
        assertEquals(HttpStatus.ACCEPTED, response.getStatus());
        response.setStatusCode(null);
        assertEquals(HttpStatus.ACCEPTED, response.getStatus(), "null 状态不应覆盖");
    }

    @Test
    void setTimeout_nullOrNegative_returnsNull() {
        TestResponse response = new TestResponse(mock(WebContext.class));
        assertNull(response.setTimeout(null, 5000));
        assertNull(response.setTimeout(() -> {
        }, -1));
    }
}
