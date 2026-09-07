package org.springframework.web.servlet.mvc.method.annotation;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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
        TestEmitter emitter = new TestEmitter();
        emitter.send("data", null);
        // 未初始化 sender 时数据应进入早发缓冲（原样入队，不包裹 DataWithMediaType）
        assertEquals(1, emitter.earlySendSize());
        assertSame("data", emitter.earlySendAt(0));
    }

    @Test
    void send_withMediaType_wrapsInDataWithMediaType() throws Exception {
        TestEmitter emitter = new TestEmitter();
        emitter.send("data", MediaType.TEXT_PLAIN);
        // 带 MediaType 时应包裹为 DataWithMediaType 入早发缓冲
        assertEquals(1, emitter.earlySendSize());
        assertTrue(emitter.earlySendAt(0) instanceof ResponseBodyEmitter.DataWithMediaType);
        ResponseBodyEmitter.DataWithMediaType wrapper =
                (ResponseBodyEmitter.DataWithMediaType) emitter.earlySendAt(0);
        assertSame("data", wrapper.getData());
        assertEquals(MediaType.TEXT_PLAIN, wrapper.getMediaType());
    }

    @Test
    void send_withoutMediaType_callsSend() throws Exception {
        TestEmitter emitter = new TestEmitter();
        emitter.send("data");
        assertEquals(1, emitter.earlySendSize());
        assertSame("data", emitter.earlySendAt(0));
    }

    @Test
    void send_multipleTimes_accumulates() throws Exception {
        TestEmitter emitter = new TestEmitter();
        emitter.send("first");
        emitter.send("second");
        assertEquals(2, emitter.earlySendSize());
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
    void encode_delegatesToFunction() throws Exception {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        emitter.setEncodeFunction((data, out) -> out.write("encoded".getBytes(StandardCharsets.UTF_8)));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        emitter.encode("test", baos);
        assertArrayEquals("encoded".getBytes(StandardCharsets.UTF_8), baos.toByteArray());
    }

    @Test
    void toString_containsIdentity() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        String str = emitter.toString();
        assertTrue(str.contains("ResponseBodyEmitter"));
    }

    @Test
    void complete_marksAsDone() {
        TestEmitter emitter = new TestEmitter();
        assertFalse(emitter.isComplete());
        emitter.complete();
        assertTrue(emitter.isComplete(), "complete() 后内部完成标志应置位");
    }

    @Test
    void completeWithError_marksAsError() {
        TestEmitter emitter = new TestEmitter();
        assertFalse(emitter.isComplete());
        emitter.completeWithError(new RuntimeException("test error"));
        assertTrue(emitter.isComplete(), "completeWithError() 后内部完成标志应置位");
    }

    /**
     * 测试用子类：暴露 StreamEmitter 内部状态（earlySendDataList / complete）供断言。
     */
    private static class TestEmitter extends ResponseBodyEmitter {
        synchronized boolean isComplete() {
            return complete.get();
        }

        synchronized int earlySendSize() {
            return earlySendDataList.size();
        }

        synchronized Object earlySendAt(int i) {
            return earlySendDataList.get(i);
        }
    }
}