package org.springframework.web.servlet.mvc.method.annotation;

public class AdapterUtil {

    public static void setEncodeToBytesFunction(ResponseBodyEmitter emitter, ResponseBodyEmitter.EncodeToBytesFunction encodeToBytesFunction) {
        emitter.setEncodeToBytesFunction(encodeToBytesFunction);
    }
}
