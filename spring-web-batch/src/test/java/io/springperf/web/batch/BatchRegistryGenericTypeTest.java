package io.springperf.web.batch;

import io.springperf.web.batch.common.BatchRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

import static org.assertj.core.api.Assertions.assertThat;

@SuppressWarnings("unchecked")
class BatchRegistryGenericTypeTest {

    static class DirectRequest extends BatchRequest<String> {
        public DirectRequest(String msg) {
        }
    }

    static class MiddleRequest extends DirectRequest {
        public MiddleRequest(String msg) {
            super(msg);
        }
    }

    static class DeepRequest extends MiddleRequest {
        public DeepRequest(String msg) {
            super(msg);
        }
    }

    private Type invokeResolveGenericType(Class<?> requestType) {
        Method resolve = ReflectionUtils.findMethod(
                BatchRegistry.class, "resolveBatchRequestGenericType", Class.class);
        assertThat(resolve).isNotNull();
        resolve.setAccessible(true);
        return (Type) ReflectionUtils.invokeMethod(resolve, null, requestType);
    }

    private static void assertBatchRequestStringType(Type type) {
        assertThat(type).isInstanceOf(ParameterizedType.class);
        ParameterizedType pType = (ParameterizedType) type;
        assertThat(pType.getRawType()).isEqualTo(BatchRequest.class);
        assertThat(pType.getActualTypeArguments()[0]).isEqualTo(String.class);
    }

    @Test
    void directSubclass_resolvesToBatchRequestString() {
        Type type = invokeResolveGenericType(DirectRequest.class);
        assertBatchRequestStringType(type);
    }

    @Test
    void multiLevelInheritance_resolvesToBatchRequestString() {
        Type type = invokeResolveGenericType(DeepRequest.class);
        assertBatchRequestStringType(type);
    }

    @Test
    void middleClass_resolvesToBatchRequestString() {
        Type type = invokeResolveGenericType(MiddleRequest.class);
        assertBatchRequestStringType(type);
    }

    @Test
    void createEffectiveReturnType_genericParameterMatches() throws Exception {
        Method createMethod = ReflectionUtils.findMethod(
                BatchRegistry.class, "createEffectiveReturnType",
                io.springperf.web.batch.common.BatchRequestMetaData.class);
        assertThat(createMethod).isNotNull();
        createMethod.setAccessible(true);

        java.lang.reflect.Constructor<?> ctor = DirectRequest.class.getDeclaredConstructor(String.class);
        Method dummyMethod = Object.class.getDeclaredMethod("toString");
        io.springperf.web.batch.common.BatchRequestMetaData meta =
                new io.springperf.web.batch.common.BatchRequestMetaData(
                        dummyMethod, DirectRequest.class, DirectRequest.class, "test-queue",
                        1024, io.springperf.web.batch.annotation.BatchMapping.WaitStrategy.BLOCKING,
                        io.springperf.web.batch.annotation.BatchMapping.Backpressure.BLOCK,
                        ctor, 100, 4);

        MethodParameter mp = (MethodParameter) ReflectionUtils.invokeMethod(createMethod, null, meta);
        assertThat(mp).isNotNull();
        assertThat(mp.getParameterType()).isEqualTo(DirectRequest.class);
        assertBatchRequestStringType(mp.getGenericParameterType());
    }
}