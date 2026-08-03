package org.springframework.web.servlet.mvc.method.annotation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdapterUtilTest {

    @Test
    void setEncodeFunction_setsFunctionOnEmitter() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        ResponseBodyEmitter.EncodeFunction encodeFn = (data, out) -> out.write(new byte[0]);

        AdapterUtil.setEncodeFunction(emitter, encodeFn);

        // No exception means success
        assertNotNull(emitter);
    }

    @Test
    void setEncodeFunction_withNullFunction() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();

        assertDoesNotThrow(() -> AdapterUtil.setEncodeFunction(emitter, null));
    }
}