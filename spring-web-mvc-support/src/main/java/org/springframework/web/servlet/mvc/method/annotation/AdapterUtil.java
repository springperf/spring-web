package org.springframework.web.servlet.mvc.method.annotation;

public class AdapterUtil {

    public static void setEncodeFunction(ResponseBodyEmitter emitter, ResponseBodyEmitter.EncodeFunction encodeFunction) {
        emitter.setEncodeFunction(encodeFunction);
    }
}
