package org.springframework.web.servlet.mvc.method.annotation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdapterUtilTest {

    @Test
    void setEncodeFunction_appliesFunctionToEmitter() throws Exception {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        AdapterUtil.setEncodeFunction(emitter,
                (data, out) -> out.write(("encoded:" + data).getBytes(StandardCharsets.UTF_8)));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        emitter.encode("body", out);

        // 断言编码结果，而非 emitter 非空：setEncodeFunction 若退化为空实现，此处必然失败
        assertEquals("encoded:body", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void setEncodeFunction_withNullFunction_leavesEmitterWithoutEncoding() throws Exception {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        AdapterUtil.setEncodeFunction(emitter, (data, out) -> out.write("x".getBytes(StandardCharsets.UTF_8)));
        AdapterUtil.setEncodeFunction(emitter, null);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        emitter.encode("body", out);

        assertEquals(0, out.size(), "encodeFunction 为 null 时应静默不写出任何字节");
    }
}
