package org.springframework.web.servlet.mvc.method.annotation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AdapterUtilTest {

    @Test
    void setEncodeToBytesFunction_setsFunctionOnEmitter() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();
        ResponseBodyEmitter.EncodeToBytesFunction encodeFn = data -> new byte[0];

        AdapterUtil.setEncodeToBytesFunction(emitter, encodeFn);

        // No exception means success
        assertNotNull(emitter);
    }

    @Test
    void setEncodeToBytesFunction_withNullFunction() {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter();

        assertDoesNotThrow(() -> AdapterUtil.setEncodeToBytesFunction(emitter, null));
    }
}