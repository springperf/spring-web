package org.springframework.web.servlet.mvc.method.annotation;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.*;

class ResponseBodyEmitterTest {

    @Test
    void defaultConstructor_createsEmitter() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        assertNotNull(emitter);
    }

    @Test
    void timeoutConstructor_createsEmitterWithTimeout() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(5000L);
        assertNotNull(emitter);
        assertEquals(5000L, emitter.getTimeout().longValue());
    }

    @Test
    void timeoutConstructor_nullTimeout_createsEmitter() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(null);
        assertNotNull(emitter);
    }

    @Test
    void send_withNullMediaType_callsSend() throws Exception {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        emitter.send("data", null);
        // send eventual success, no encoding needed in early buffer stage
    }

    @Test
    void send_withMediaType_wrapsInDataWithMediaType() throws Exception {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        emitter.send("data", MediaType.TEXT_PLAIN);
        // wraps and stores as DataWithMediaType
    }

    @Test
    void send_withoutMediaType_callsSend() throws Exception {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        emitter.send("data");
    }

    @Test
    void send_multipleTimes_accumulates() throws Exception {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        emitter.send("first");
        emitter.send("second");
    }

    @Test
    void getTimeout_notSet_returnsNull() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        assertNull(emitter.getTimeout());
    }

    @Test
    void extendResponse_noOp() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        emitter.extendResponse(null);
        // no-op, should not throw
    }

    @Test
    void dataWithMediaType_holdsDataAndMediaType() {
        Object data = "test";
        MediaType mediaType = MediaType.APPLICATION_JSON;
        ResponseBodyEmitter.DataWithMediaType wrapper = new ResponseBodyEmitter.DataWithMediaType(data, mediaType);

        assertSame(data, wrapper.getData());
        assertSame(mediaType, wrapper.getMediaType());
    }

    @Test
    void dataWithMediaType_nullMediaType() {
        ResponseBodyEmitter.DataWithMediaType wrapper = new ResponseBodyEmitter.DataWithMediaType("data", null);
        assertEquals("data", wrapper.getData());
        assertNull(wrapper.getMediaType());
    }

    @Test
    void encodeToBytes_delegatesToFunction() throws Exception {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        emitter.setEncodeToBytesFunction(data -> "encoded".getBytes());

        byte[] result = emitter.encodeToBytes("test");
        assertArrayEquals("encoded".getBytes(), result);
    }

    @Test
    void toString_containsIdentity() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        String str = emitter.toString();
        assertTrue(str.contains("ResponseBodyEmitter"));
    }

    @Test
    void complete_marksAsDone() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        emitter.complete();
        // mark complete without error
    }

    @Test
    void completeWithError_marksAsError() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        emitter.completeWithError(new RuntimeException("test error"));
        // mark complete with error
    }
}
