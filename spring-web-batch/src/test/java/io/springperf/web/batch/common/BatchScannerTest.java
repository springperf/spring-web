package io.springperf.web.batch.common;

import org.junit.jupiter.api.Test;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

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

    static class RawListHandler {
        @SuppressWarnings("unused")
        public void handle(List requests) {
        }
    }

    static class MapHandler {
        @SuppressWarnings("unused")
        public void handle(Map<String, EchoBatchRequest> requests) {
        }
    }

    static class EchoBatchRequest extends BatchRequest<String> {
    }

    private Class<? extends BatchRequest<?>> invokeResolveRequestType(Method batchMethod) {
        Method resolveRequestType = ReflectionUtils.findMethod(BatchScanner.class, "resolveRequestType", Method.class);
        assertThat(resolveRequestType).isNotNull();
        resolveRequestType.setAccessible(true);
        return (Class<? extends BatchRequest<?>>) ReflectionUtils.invokeMethod(resolveRequestType, new BatchScanner(),
                batchMethod);
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
    void resolveRequestType_rawList_returnsNull() throws Exception {
        // 原始类型 List（无泛型实参）无法确定 BatchRequest 子类型
        Method method = RawListHandler.class.getDeclaredMethod("handle", List.class);
        assertThat(invokeResolveRequestType(method)).isNull();
    }

    @Test
    void resolveRequestType_mapFirstParam_returnsNull() throws Exception {
        // 首个参数不是 List，即使内部泛型是 BatchRequest 子类也不合法
        Method method = MapHandler.class.getDeclaredMethod("handle", Map.class);
        assertThat(invokeResolveRequestType(method)).isNull();
    }

    @Test
    void resolveRequestType_noParameter_returnsNull() throws Exception {
        Method noParam = BatchScannerTest.class.getDeclaredMethod("noParamMethod");
        Class<? extends BatchRequest<?>> result = invokeResolveRequestType(noParam);
        assertThat(result).isNull();
    }

    @SuppressWarnings("unused")
    public void noParamMethod() {
    }
}
