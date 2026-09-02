package io.springperf.web.batch.common;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.core.mapping.PathMappingContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Controller;
import org.springframework.web.method.HandlerMethod;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BatchScannerScanTest {

    public static class EchoRequest extends BatchRequest<String> {
        public EchoRequest(String data) {
            this.setResult(data);
        }
    }

    @Controller
    public static class BatchController {
        @BatchMapping(method = "single", ringBufferSize = 64, maxBatchSize = 5, consumerSize = 2)
        public void batch(List<EchoRequest> requests) {
        }

        public void single(String data) {
        }
    }

    @Controller
    public static class PlainController {
        public void single(String data) {
        }
    }

    @Controller
    public static class BadRequestTypeController {
        @BatchMapping
        public void bad(List<String> requests) {
        }

        public void bad(String data) {
        }
    }

    @Controller
    public static class TooManyParamsController {
        @BatchMapping
        public void bad(List<EchoRequest> requests, String extra) {
        }

        public void bad(EchoRequest req) {
        }
    }

    @Controller
    public static class DefaultConsumerController {
        @BatchMapping(method = "single")
        public void batch(List<EchoRequest> requests) {
        }

        public void single(String data) {
        }
    }

    @Configuration
    static class TestConfig {
        @Bean
        public BatchController batchController() {
            return new BatchController();
        }

        @Bean
        public PlainController plainController() {
            return new PlainController();
        }
    }

    private PathMappingContext mappingFor(Object bean, String methodName, Class<?>... paramTypes) throws Exception {
        HandlerMethod hm = new HandlerMethod(bean, bean.getClass().getMethod(methodName, paramTypes));
        return new PathMappingContext(hm, Collections.emptyList(), "/" + methodName);
    }

    @Test
    void scan_discoversBatchMethodAndResolvesMetadata() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            BatchController bean = ctx.getBean(BatchController.class);
            PathMappingContext singleCtx = mappingFor(bean, "single", String.class);
            BatchScanner scanner = new BatchScanner();
            List<BatchHandlerRegistration> result = scanner.scan(ctx, Collections.singletonList(singleCtx));

            assertEquals(1, result.size());
            BatchHandlerRegistration reg = result.get(0);
            assertSame(bean, reg.bean());
            assertSame(singleCtx, reg.singleCtx());
            BatchRequestMetaData meta = reg.meta();
            assertEquals(EchoRequest.class, meta.requestType());
            assertEquals("batch:BatchController.batch", meta.queueName());
            assertEquals(64, meta.ringBufferSize());
            assertEquals(5, meta.maxBatchSize());
            assertEquals(2, meta.consumerSize());
            assertNotNull(meta.singleMethodCtor());
        }
    }

    @Test
    void scan_noBatchMethods_returnsEmpty() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(PlainController.class);
            ctx.refresh();
            PlainController bean = ctx.getBean(PlainController.class);
            PathMappingContext singleCtx = mappingFor(bean, "single", String.class);
            BatchScanner scanner = new BatchScanner();
            List<BatchHandlerRegistration> result = scanner.scan(ctx, Collections.singletonList(singleCtx));
            assertTrue(result.isEmpty());
        }
    }

    @Test
    void scan_emptyMappings_singleNotFound_throws() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext(TestConfig.class)) {
            BatchScanner scanner = new BatchScanner();
            assertThrows(IllegalStateException.class,
                    () -> scanner.scan(ctx, Collections.emptyList()));
        }
    }

    @Test
    void scan_badRequestType_throws() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(BadRequestTypeController.class);
            ctx.refresh();
            BadRequestTypeController bean = ctx.getBean(BadRequestTypeController.class);
            PathMappingContext singleCtx = mappingFor(bean, "bad", String.class);
            BatchScanner scanner = new BatchScanner();
            assertThrows(IllegalStateException.class,
                    () -> scanner.scan(ctx, Collections.singletonList(singleCtx)));
        }
    }

    @Test
    void scan_tooManyParams_throws() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(TooManyParamsController.class);
            ctx.refresh();
            TooManyParamsController bean = ctx.getBean(TooManyParamsController.class);
            PathMappingContext singleCtx = mappingFor(bean, "bad", EchoRequest.class);
            BatchScanner scanner = new BatchScanner();
            assertThrows(IllegalStateException.class,
                    () -> scanner.scan(ctx, Collections.singletonList(singleCtx)));
        }
    }

    @Test
    void scan_consumerSizeDefault_usesAvailableProcessors() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(DefaultConsumerController.class);
            ctx.refresh();
            DefaultConsumerController bean = ctx.getBean(DefaultConsumerController.class);
            PathMappingContext singleCtx = mappingFor(bean, "single", String.class);
            BatchScanner scanner = new BatchScanner();
            List<BatchHandlerRegistration> result = scanner.scan(ctx, Collections.singletonList(singleCtx));
            assertEquals(1, result.size());
            assertEquals(Runtime.getRuntime().availableProcessors(), result.get(0).meta().consumerSize());
        }
    }
}
