package io.springperf.web.core.async.stream;

import io.springperf.web.core.async.AsyncSupportRegistry;
import io.springperf.web.http.WebServerHttpRequest;
import io.springperf.web.http.WebServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.server.ServerHttpResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamEmitterTest {

    @Mock
    StreamSender streamSender;

    @Mock
    ServerHttpResponse serverHttpResponse;

    @Mock
    StreamSenderFactory streamSenderFactory;

    @Mock
    AsyncSupportRegistry asyncSupportRegistry;

    @Mock
    WebServerHttpRequest request;

    @Mock
    WebServerHttpResponse response;

    private StreamEmitter createEmitter() {
        return new StreamEmitter<Object>() {
            @Override
            public void encode(Object data, java.io.OutputStream out) {
                throw new UnsupportedOperationException();
            }
            @Override
            protected void extendResponse(ServerHttpResponse response) {
            }
        };
    }

    @Test
    void send_beforeInitialize_queuesData() throws Exception {
        StreamEmitter emitter = createEmitter();

        emitter.send("data1");
        emitter.send("data2");

        assertEquals(2, emitter.earlySendDataList.size());
    }

    @Test
    void send_afterInitialize_sendsDirectly() throws Exception {
        StreamEmitter emitter = createEmitter();
        emitter.initialize(streamSender);

        emitter.send("data");

        verify(streamSender).send("data");
    }

    @Test
    void initialize_sendsQueuedData() throws Exception {
        StreamEmitter emitter = createEmitter();
        emitter.send("queued1");
        emitter.send("queued2");

        List<Object> captured = snapshotSendAll();
        emitter.initialize(streamSender);

        // sendAll 收到的是 earlySendDataList 的同一引用，initialize 的 finally 会 clear，
        // 所以必须用快照断言调用瞬间的内容
        assertEquals(Arrays.asList("queued1", "queued2"), captured);
        assertTrue(emitter.earlySendDataList.isEmpty());
    }

    @Test
    void initialize_sendError_clearEarlyData() throws Exception {
        StreamEmitter emitter = createEmitter();
        emitter.send("data");
        doThrow(new IOException("send error")).when(streamSender).sendAll(any());

        try {
            emitter.initialize(streamSender);
        } catch (IOException ignored) {
        }

        assertTrue(emitter.earlySendDataList.isEmpty());
    }

    /**
     * 让 mock 的 sendAll 在调用瞬间把参数内容快照到返回的 List。
     * 因为 sendAll 接收 earlySendDataList 同一引用，事后读取已被 clear。
     */
    private List<Object> snapshotSendAll() throws Exception {
        List<Object> captured = new ArrayList<>();
        doAnswer(invocation -> {
            List<?> batch = invocation.getArgument(0);
            captured.addAll(batch);
            return null;
        }).when(streamSender).sendAll(any());
        return captured;
    }

    @Test
    void complete_idempotent() {
        StreamEmitter emitter = createEmitter();

        emitter.complete();
        emitter.complete(); // second call should be no-op

        assertTrue(emitter.complete.get());
    }

    @Test
    void completeWithError_idempotent() {
        StreamEmitter emitter = createEmitter();

        emitter.completeWithError(new RuntimeException("error1"));
        emitter.completeWithError(new RuntimeException("error2")); // should be no-op

        assertTrue(emitter.complete.get());
    }

    @Test
    void complete_sendsToStreamSender() throws Exception {
        StreamEmitter emitter = createEmitter();
        emitter.initialize(streamSender);

        emitter.complete();

        // complete triggers deferredResult.setResult(null) first, then streamSender.complete
        verify(streamSender, timeout(100)).complete(false, null);
    }

    @Test
    void completeWithError_sendsToStreamSender() throws Exception {
        StreamEmitter emitter = createEmitter();
        emitter.initialize(streamSender);
        RuntimeException ex = new RuntimeException("fail");

        emitter.completeWithError(ex);

        // completeWithError triggers deferredResult.setErrorResult(ex) first, then streamSender.complete
        verify(streamSender, timeout(100)).complete(false, ex);
    }

    @Test
    void initializeWithError_clearsEarlyDataAndSetsError() throws Exception {
        StreamEmitter emitter = createEmitter();
        emitter.send("data");

        RuntimeException ex = new RuntimeException("init error");
        emitter.initializeWithError(ex);

        assertTrue(emitter.earlySendDataList.isEmpty());
        assertTrue(emitter.complete.get());
        assertTrue(emitter.deferredResult.hasResult());
        assertSame(ex, emitter.deferredResult.getResult());
    }

    @Test
    void initializeWithError_idempotent() {
        StreamEmitter emitter = createEmitter();

        emitter.initializeWithError(new RuntimeException("first"));
        emitter.initializeWithError(new RuntimeException("second"));

        assertTrue(emitter.complete.get());
    }

    @Test
    void onTimeout_calledWhenTimeoutFires() throws Exception {
        StreamEmitter emitter = createEmitter();
        Runnable timeoutHandler = mock(Runnable.class);

        emitter.onTimeout(timeoutHandler);

        // 反射获取 DeferredResult 的 timeoutCallback，模拟 DeferredResult 超时触发回调
        java.lang.reflect.Field field = org.springframework.web.context.request.async.DeferredResult.class
                .getDeclaredField("timeoutCallback");
        field.setAccessible(true);
        Runnable invoked = (Runnable) field.get(emitter.deferredResult);
        assertNotNull(invoked);
        invoked.run();

        verify(timeoutHandler).run();
    }

    @Test
    void getMaxFlushBytes_default_returns16k() {
        StreamEmitter emitter = createEmitter();

        assertEquals(16384, emitter.getMaxFlushBytes());
    }

    @Test
    void constructor_withTimeout() {
        StreamEmitter emitter = new StreamEmitter<Object>(5000L) {
            @Override
            public void encode(Object data, java.io.OutputStream out) {
                throw new UnsupportedOperationException();
            }
            @Override
            protected void extendResponse(ServerHttpResponse response) {
            }
        };

        assertNotNull(emitter.deferredResult);
    }

    @Test
    void constructor_noTimeout() {
        StreamEmitter emitter = new StreamEmitter<Object>((Long) null) {
            @Override
            public void encode(Object data, java.io.OutputStream out) {
                throw new UnsupportedOperationException();
            }
            @Override
            protected void extendResponse(ServerHttpResponse response) {
            }
        };

        assertNotNull(emitter.deferredResult);
    }

    @Test
    void constructor_nonPositiveTimeout_usesNoTimeout() {
        StreamEmitter emitter = new StreamEmitter<Object>(0L) {
            @Override
            public void encode(Object data, java.io.OutputStream out) {
                throw new UnsupportedOperationException();
            }
            @Override
            protected void extendResponse(ServerHttpResponse response) {
            }
        };

        assertNotNull(emitter.deferredResult);
    }

    @Test
    void complete_beforeInitialize_delaysDeferredResult() throws Exception {
        StreamEmitter emitter = createEmitter();
        emitter.send("early");

        emitter.complete(); // streamSender=null, should NOT trigger deferredResult or streamSender

        assertTrue(emitter.complete.get());
        assertFalse(emitter.deferredResult.hasResult());
        assertEquals(1, emitter.earlySendDataList.size());

        List<Object> captured = snapshotSendAll();
        emitter.initialize(streamSender);

        assertEquals(Arrays.asList("early"), captured);
        verify(streamSender, timeout(100)).complete(false, null);
    }

    @Test
    void encode_throwsUnsupportedOperation() {
        StreamEmitter emitter = createEmitter();

        assertThrows(UnsupportedOperationException.class, () -> emitter.encode("test", new java.io.ByteArrayOutputStream()));
    }
}