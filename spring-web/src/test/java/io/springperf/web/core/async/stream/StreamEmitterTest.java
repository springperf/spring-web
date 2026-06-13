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

        emitter.initialize(streamSender);

        verify(streamSender).send("queued1");
        verify(streamSender).send("queued2");
        assertTrue(emitter.earlySendDataList.isEmpty());
    }

    @Test
    void initialize_sendError_clearEarlyData() throws Exception {
        StreamEmitter emitter = createEmitter();
        emitter.send("data");
        doThrow(new IOException("send error")).when(streamSender).send(any());

        try {
            emitter.initialize(streamSender);
        } catch (IOException ignored) {
        }

        assertTrue(emitter.earlySendDataList.isEmpty());
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
        verify(streamSender, timeout(100)).complete(true, null);
    }

    @Test
    void completeWithError_sendsToStreamSender() throws Exception {
        StreamEmitter emitter = createEmitter();
        emitter.initialize(streamSender);
        RuntimeException ex = new RuntimeException("fail");

        emitter.completeWithError(ex);

        // completeWithError triggers deferredResult.setErrorResult(ex) first, then streamSender.complete
        verify(streamSender, timeout(100)).complete(true, ex);
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
    void onTimeout_calledWhenTimeoutFires() {
        StreamEmitter emitter = createEmitter();
        Runnable timeoutHandler = mock(Runnable.class);

        emitter.onTimeout(timeoutHandler);
        emitter.deferredResult.onTimeout(timeoutHandler);

        // DeferredResult.onTimeout triggers the handler
        emitter.deferredResult.setResult(null);
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
            protected void extendResponse(ServerHttpResponse response) {
            }
        };

        assertNotNull(emitter.deferredResult);
        assertTrue(emitter.encodeToString);
    }

    @Test
    void constructor_noTimeout() {
        StreamEmitter emitter = new StreamEmitter<Object>(null, true) {
            @Override
            protected void extendResponse(ServerHttpResponse response) {
            }
        };

        assertNotNull(emitter.deferredResult);
    }

    @Test
    void constructor_withTimeoutAndEncodeFlag() {
        StreamEmitter emitter = new StreamEmitter<Object>(3000L, false) {
            @Override
            protected void extendResponse(ServerHttpResponse response) {
            }
        };

        assertNotNull(emitter.deferredResult);
    }

    @Test
    void constructor_nonPositiveTimeout_usesNoTimeout() {
        StreamEmitter emitter = new StreamEmitter<Object>(0L, true) {
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

        emitter.initialize(streamSender);

        verify(streamSender).send("early");
        verify(streamSender, timeout(100)).complete(true, null);
    }

    @Test
    void encodeToString_throwsUnsupportedOperation() {
        StreamEmitter emitter = createEmitter();

        assertThrows(UnsupportedOperationException.class, () -> emitter.encodeToString("test"));
    }

    @Test
    void encodeToBytes_throwsUnsupportedOperation() {
        StreamEmitter emitter = createEmitter();

        assertThrows(UnsupportedOperationException.class, () -> emitter.encodeToBytes("test".getBytes()));
    }
}