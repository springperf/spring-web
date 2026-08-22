package io.springperf.web.batch.common;

import org.junit.jupiter.api.Test;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class BatchScannerTest {

    static class RawTypeHandler {
        @SuppressWarnings("unused")
        public void handle(List<EchoBatchRequest> requests) {
        }
    }

    static class ParameterizedTypeHandler {
        @SuppressWarnings("unused")
        public void handle(List<BatchRequest<String>> requests) {
        }
    }

    static class EchoBatchRequest extends BatchRequest<String> {
    }

    private Class<? extends BatchRequest<?>> invokeResolveRequestType(Method batchMethod) {
        Method resolveRequestType = ReflectionUtils.findMethod(BatchScanner.class, "resolveRequestType", Method.class);
        assertThat(resolveRequestType).isNotNull();
        resolveRequestType.setAccessible(true);
        return (Class<? extends BatchRequest<?>>) ReflectionUtils.invokeMethod(resolveRequestType, new BatchScanner(), batchMethod);
    }

    @Test
    void resolveRequestType_rawClass() throws Exception {
        Method method = RawTypeHandler.class.getDeclaredMethod("handle", List.class);
        Class<? extends BatchRequest<?>> result = invokeResolveRequestType(method);
        assertThat(result).isEqualTo(EchoBatchRequest.class);
    }

    @Test
    void resolveRequestType_parameterizedType() throws Exception {
        Method method = ParameterizedTypeHandler.class.getDeclaredMethod("handle", List.class);
        Class<? extends BatchRequest<?>> result = invokeResolveRequestType(method);
        assertThat(result).isEqualTo(BatchRequest.class);
    }

    @Test
    void resolveRequestType_noParameter_returnsNull() throws Exception {
        Method method = RawTypeHandler.class.getDeclaredMethod("handle", List.class);
        Method noParam = BatchScannerTest.class.getDeclaredMethod("noParamMethod");
        Class<? extends BatchRequest<?>> result = invokeResolveRequestType(noParam);
        assertThat(result).isNull();
    }

    @SuppressWarnings("unused")
    public void noParamMethod() {
    }
}