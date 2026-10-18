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

    @Controller
    public static class OverloadedSingleController {
        @BatchMapping(method = "single")
        public void batch(List<EchoRequest> requests) {
        }

        public void single(String data) {
        }

        @SuppressWarnings("unused")
        public void single(Integer number) {
        }
    }

    public static class NoCtorRequest extends BatchRequest<String> {
        public NoCtorRequest() {
            super();
        }
    }

    @Controller
    public static class NoMatchingCtorController {
        @BatchMapping(method = "single")
        public void batch(List<NoCtorRequest> requests) {
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
            assertThrows(IllegalStateException.class, () -> scanner.scan(ctx, Collections.emptyList()));
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
            assertThrows(IllegalStateException.class, () -> scanner.scan(ctx, Collections.singletonList(singleCtx)));
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
            assertThrows(IllegalStateException.class, () -> scanner.scan(ctx, Collections.singletonList(singleCtx)));
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

    @Test
    void scan_overloadedSingleMethod_throws() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(OverloadedSingleController.class);
            ctx.refresh();
            OverloadedSingleController bean = ctx.getBean(OverloadedSingleController.class);
            PathMappingContext singleStr = mappingFor(bean, "single", String.class);
            PathMappingContext singleInt = mappingFor(bean, "single", Integer.class);
            BatchScanner scanner = new BatchScanner();
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> scanner.scan(ctx, java.util.Arrays.asList(singleStr, singleInt)));
            assertTrue(ex.getMessage().contains("distinct methods"), "同名单方法重载应被视为歧义并抛异常: " + ex.getMessage());
        }
    }

    @Test
    void scan_sameMethodMappedMultipleTimes_isNotAmbiguous() throws Exception {
        // 同一 Java 方法被多个 @RequestMapping 路径注册时产生多个 PathMappingContext，
        // 歧义判定按 Method 身份而非条目数——不应误报
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(BatchController.class);
            ctx.refresh();
            BatchController bean = ctx.getBean(BatchController.class);
            PathMappingContext a = mappingFor(bean, "single", String.class);
            PathMappingContext b = mappingFor(bean, "single", String.class);
            BatchScanner scanner = new BatchScanner();
            List<BatchHandlerRegistration> result = scanner.scan(ctx, java.util.Arrays.asList(a, b));

            assertEquals(1, result.size());
            assertSame(a, result.get(0).singleCtx(), "应取首个匹配的映射上下文");
        }
    }

    @Controller
    public static class GenericTypeVariableController {
        @BatchMapping(method = "single")
        public <T> void batch(List<T> requests) {
        }

        public void single(String data) {
        }
    }

    @Test
    void scan_typeVariableElement_notABatchRequest_throws() throws Exception {
        // List<T> 的类型实参是 TypeVariable：既不是 Class 也不是 ParameterizedType，
        // 两条解析路径都不命中，必须落到最后的 return null 上
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(GenericTypeVariableController.class);
            ctx.refresh();
            GenericTypeVariableController bean = ctx.getBean(GenericTypeVariableController.class);
            PathMappingContext singleCtx = mappingFor(bean, "single", String.class);
            BatchScanner scanner = new BatchScanner();
            assertThrows(IllegalStateException.class, () -> scanner.scan(ctx, Collections.singletonList(singleCtx)));
        }
    }

    @Test
    void scan_requestTypeWithoutMatchingCtor_throws() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(NoMatchingCtorController.class);
            ctx.refresh();
            NoMatchingCtorController bean = ctx.getBean(NoMatchingCtorController.class);
            PathMappingContext singleCtx = mappingFor(bean, "single", String.class);
            BatchScanner scanner = new BatchScanner();
            assertThrows(IllegalStateException.class, () -> scanner.scan(ctx, Collections.singletonList(singleCtx)),
                    "BatchRequest 缺少与 single 方法参数匹配的构造函数应抛异常");
        }
    }

    /* ==================== 回归 P1：@BatchMapping 声明在父类 ==================== */

    /** 批量方法声明在父类——MappingRegistry 会为它注册单请求映射，BatchScanner 也必须发现它。 */
    public abstract static class AbstractBatchController {
        @BatchMapping(method = "single", ringBufferSize = 64, maxBatchSize = 3, consumerSize = 2)
        public void batch(List<EchoRequest> requests) {
        }
    }

    @Controller
    public static class InheritedBatchController extends AbstractBatchController {
        public void single(String data) {
        }
    }

    @Test
    void scan_batchMethodDeclaredOnSuperClass_isDiscovered() throws Exception {
        // 回归：扫描器曾用 getDeclaredMethods()，父类声明的 @BatchMapping 不会被安装，
        // 单请求方法体不被替换，请求走到用户实现的 return null —— 表现为静默空响应
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(InheritedBatchController.class);
            ctx.refresh();
            InheritedBatchController bean = ctx.getBean(InheritedBatchController.class);
            PathMappingContext singleCtx = mappingFor(bean, "single", String.class);
            BatchScanner scanner = new BatchScanner();
            List<BatchHandlerRegistration> result = scanner.scan(ctx, Collections.singletonList(singleCtx));

            assertEquals(1, result.size(), "父类声明的 @BatchMapping 应被发现");
            assertEquals("batch:InheritedBatchController.batch", result.get(0).meta().queueName());
            assertEquals(64, result.get(0).meta().ringBufferSize());
            assertEquals(3, result.get(0).meta().maxBatchSize());
        }
    }

    /* ==================== 单请求方法解析的 fail-fast 路径 ==================== */

    @Controller
    public static class NoSuchSingleController {
        @BatchMapping(method = "missing")
        public void batch(List<EchoRequest> requests) {
        }

        public void unrelated(String data) {
        }
    }

    @Test
    void scan_singleMethodNotFound_throws() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(NoSuchSingleController.class);
            ctx.refresh();
            NoSuchSingleController bean = ctx.getBean(NoSuchSingleController.class);
            PathMappingContext singleCtx = mappingFor(bean, "unrelated", String.class);
            BatchScanner scanner = new BatchScanner();
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> scanner.scan(ctx, Collections.singletonList(singleCtx)));
            assertTrue(ex.getMessage().contains("Cannot find single-request method"),
                    "报错应指明缺失的单方法: " + ex.getMessage());
        }
    }

    @Controller
    public static class RawListController {
        @BatchMapping
        public void bad(List requests) {
        }

        public void bad(String data) {
        }
    }

    @Test
    void scan_rawListFirstParam_throws() throws Exception {
        // 原始类型 List（无泛型参数）无法提取 BatchRequest 子类型
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(RawListController.class);
            ctx.refresh();
            RawListController bean = ctx.getBean(RawListController.class);
            PathMappingContext singleCtx = mappingFor(bean, "bad", String.class);
            BatchScanner scanner = new BatchScanner();
            assertThrows(IllegalStateException.class, () -> scanner.scan(ctx, Collections.singletonList(singleCtx)));
        }
    }

    @Controller
    public static class NonListFirstParamController {
        @BatchMapping
        public void bad(java.util.Map<String, EchoRequest> requests) {
        }

        public void bad(String data) {
        }
    }

    @Test
    void scan_nonListFirstParam_throws() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(NonListFirstParamController.class);
            ctx.refresh();
            NonListFirstParamController bean = ctx.getBean(NonListFirstParamController.class);
            PathMappingContext singleCtx = mappingFor(bean, "bad", String.class);
            BatchScanner scanner = new BatchScanner();
            assertThrows(IllegalStateException.class, () -> scanner.scan(ctx, Collections.singletonList(singleCtx)));
        }
    }

    @Controller
    public static class NestedGenericController {
        @BatchMapping
        public void bad(List<List<String>> requests) {
        }

        public void bad(String data) {
        }
    }

    @Test
    void scan_nestedGenericArg_notABatchRequest_throws() throws Exception {
        // List<List<String>>：内层是参数化类型但其原始类型不是 BatchRequest 子类
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(NestedGenericController.class);
            ctx.refresh();
            NestedGenericController bean = ctx.getBean(NestedGenericController.class);
            PathMappingContext singleCtx = mappingFor(bean, "bad", String.class);
            BatchScanner scanner = new BatchScanner();
            assertThrows(IllegalStateException.class, () -> scanner.scan(ctx, Collections.singletonList(singleCtx)));
        }
    }

    public static class NoArgRequest extends BatchRequest<String> {
        public NoArgRequest() {
        }
    }

    @Controller
    public static class NoArgSingleController {
        @BatchMapping(method = "single")
        public void batch(List<NoArgRequest> requests) {
        }

        public void single() {
        }
    }

    @Test
    void scan_zeroParameterSingleMethod_resolvesNoArgConstructor() throws Exception {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(NoArgSingleController.class);
            ctx.refresh();
            NoArgSingleController bean = ctx.getBean(NoArgSingleController.class);
            PathMappingContext singleCtx = mappingFor(bean, "single");
            BatchScanner scanner = new BatchScanner();
            List<BatchHandlerRegistration> result = scanner.scan(ctx, Collections.singletonList(singleCtx));

            assertEquals(1, result.size());
            assertEquals(NoArgRequest.class, result.get(0).meta().requestType());
            assertNotNull(result.get(0).meta().singleMethodCtor());
            assertEquals(0, result.get(0).meta().singleMethodCtor().getParameterCount(), "无参单方法应解析到无参构造函数");
        }
    }
}
