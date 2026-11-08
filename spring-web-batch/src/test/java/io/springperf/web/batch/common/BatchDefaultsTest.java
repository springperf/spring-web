package io.springperf.web.batch.common;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.context.ApplicationProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.env.MockEnvironment;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 覆盖 {@code springperf.batch.*} 全局默认值：解析、缺省回落、非法值报错，以及「注解显式声明优先」的优先级语义。
 */
class BatchDefaultsTest {

    static class Fixtures {
        @BatchMapping(method = "single")
        public void silent() {
        }

        @BatchMapping(method = "single", batchWindowMillis = 7)
        public void explicitWindow() {
        }

        @BatchMapping(method = "single", ringBufferSize = 64, maxBatchSize = 5, consumerSize = 3,
                waitStrategy = BatchMapping.WaitStrategy.YIELDING, backpressure = BatchMapping.Backpressure.THROW,
                batchWindowMillis = 9)
        public void explicitAll() {
        }
    }

    private static BatchMapping annotationOf(String methodName) throws NoSuchMethodException {
        return AnnotatedElementUtils.findMergedAnnotation(Fixtures.class.getMethod(methodName), BatchMapping.class);
    }

    private static BatchDefaults from(MockEnvironment env) {
        return BatchDefaults.fromProperties(new ApplicationProperties(env));
    }

    @Test
    void standard_matchesAnnotationDefaults() {
        BatchDefaults defaults = BatchDefaults.standard();
        assertEquals(2, defaults.windowMillis());
        assertEquals(100, defaults.maxBatchSize());
        assertEquals(-1, defaults.consumerSize(), "-1 = 未声明，最终由核数兜底");
        assertEquals(4096, defaults.ringBufferSize());
        assertEquals(BatchMapping.WaitStrategy.BLOCKING, defaults.waitStrategy());
        assertEquals(BatchMapping.Backpressure.BLOCK, defaults.backpressure());
        assertTrue(defaults.configuredKeys().isEmpty(), "未配置任何键时不应报告已配置键");
    }

    @Test
    void fromProperties_nullApplicationProperties_fallsBackToStandard() {
        assertSame(BatchDefaults.standard(), BatchDefaults.fromProperties(null),
                "未接入 Spring Environment 的场景（如直接构造 BatchScanner 的测试）应与内置默认值完全一致");
    }

    @Test
    void fromProperties_emptyEnvironment_fallsBackToStandard() {
        BatchDefaults defaults = from(new MockEnvironment());
        assertEquals(BatchDefaults.standard().describe(), defaults.describe());
        assertTrue(defaults.configuredKeys().isEmpty());
    }

    @Test
    void fromProperties_overridesEveryAttribute() {
        MockEnvironment env = new MockEnvironment().withProperty(BatchDefaults.WINDOW_MILLIS, "3")
                .withProperty(BatchDefaults.MAX_BATCH_SIZE, "250").withProperty(BatchDefaults.CONSUMER_SIZE, "8")
                .withProperty(BatchDefaults.RING_BUFFER_SIZE, "16384")
                .withProperty(BatchDefaults.WAIT_STRATEGY, "yielding")
                .withProperty(BatchDefaults.BACKPRESSURE, "throw");

        BatchDefaults defaults = from(env);
        assertEquals(3, defaults.windowMillis());
        assertEquals(250, defaults.maxBatchSize());
        assertEquals(8, defaults.consumerSize());
        assertEquals(16384, defaults.ringBufferSize());
        assertEquals(BatchMapping.WaitStrategy.YIELDING, defaults.waitStrategy(), "枚举应忽略大小写");
        assertEquals(BatchMapping.Backpressure.THROW, defaults.backpressure());
        assertEquals(Arrays.asList(BatchDefaults.WINDOW_MILLIS, BatchDefaults.MAX_BATCH_SIZE, BatchDefaults.CONSUMER_SIZE,
                BatchDefaults.RING_BUFFER_SIZE, BatchDefaults.WAIT_STRATEGY, BatchDefaults.BACKPRESSURE),
                defaults.configuredKeys(), "configuredKeys 用于启动日志留痕，应按声明顺序列出真正配置的键");
    }

    @Test
    void fromProperties_enumAcceptsHyphenAndLowercase() {
        MockEnvironment env = new MockEnvironment().withProperty(BatchDefaults.WAIT_STRATEGY, " busy-spin ");
        assertEquals(BatchMapping.WaitStrategy.BUSY_SPIN, from(env).waitStrategy(),
                "busy-spin / BUSY_SPIN / Busy-Spin 都应解析为 BUSY_SPIN");
    }

    @Test
    void fromProperties_blankValue_isTreatedAsAbsent() {
        MockEnvironment env = new MockEnvironment().withProperty(BatchDefaults.WINDOW_MILLIS, "  ");
        BatchDefaults defaults = from(env);
        assertEquals(2, defaults.windowMillis(), "空白值不应把窗口解析成 0（那会静默关闭攒批）");
        assertTrue(defaults.configuredKeys().isEmpty());
    }

    @Test
    void fromProperties_nonInteger_throwsNamingKey() {
        MockEnvironment env = new MockEnvironment().withProperty(BatchDefaults.WINDOW_MILLIS, "5ms");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> from(env));
        assertTrue(ex.getMessage().contains(BatchDefaults.WINDOW_MILLIS),
                "报错必须点名键，否则用户不知道是哪个配置写错了: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("5ms"), "报错应带上原始值: " + ex.getMessage());
    }

    @Test
    void fromProperties_unknownEnum_throwsListingOptions() {
        MockEnvironment env = new MockEnvironment().withProperty(BatchDefaults.BACKPRESSURE, "BLOCKING");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> from(env));
        assertTrue(ex.getMessage().contains(BatchDefaults.BACKPRESSURE), ex.getMessage());
        assertTrue(ex.getMessage().contains("BLOCK") && ex.getMessage().contains("THROW"),
                "报错应列出可选值: " + ex.getMessage());
    }

    @Test
    void resolve_undeclaredAttribute_usesGlobalValue() throws Exception {
        BatchDefaults defaults = from(new MockEnvironment().withProperty(BatchDefaults.WINDOW_MILLIS, "3")
                .withProperty(BatchDefaults.MAX_BATCH_SIZE, "250").withProperty(BatchDefaults.CONSUMER_SIZE, "8")
                .withProperty(BatchDefaults.RING_BUFFER_SIZE, "2048")
                .withProperty(BatchDefaults.WAIT_STRATEGY, "YIELDING")
                .withProperty(BatchDefaults.BACKPRESSURE, "DROP"));
        BatchMapping ann = annotationOf("silent");

        assertEquals(3, defaults.resolveWindowMillis(ann));
        assertEquals(250, defaults.resolveMaxBatchSize(ann));
        assertEquals(8, defaults.resolveConsumerSize(ann));
        assertEquals(2048, defaults.resolveRingBufferSize(ann));
        assertEquals(BatchMapping.WaitStrategy.YIELDING, defaults.resolveWaitStrategy(ann));
        assertEquals(BatchMapping.Backpressure.DROP, defaults.resolveBackpressure(ann));
    }

    @Test
    void resolve_explicitAnnotation_winsOverGlobalValue() throws Exception {
        BatchDefaults defaults = from(new MockEnvironment().withProperty(BatchDefaults.WINDOW_MILLIS, "3")
                .withProperty(BatchDefaults.RING_BUFFER_SIZE, "2048"));
        BatchMapping all = annotationOf("explicitAll");

        assertEquals(9, defaults.resolveWindowMillis(all), "注解显式声明必须优先于全局键");
        assertEquals(64, defaults.resolveRingBufferSize(all));
        assertEquals(5, defaults.resolveMaxBatchSize(all));
        assertEquals(3, defaults.resolveConsumerSize(all));
        assertEquals(BatchMapping.WaitStrategy.YIELDING, defaults.resolveWaitStrategy(all));
        assertEquals(BatchMapping.Backpressure.THROW, defaults.resolveBackpressure(all));
    }

    @Test
    void resolve_partialAnnotation_leavesOtherAttributesToGlobal() throws Exception {
        BatchDefaults defaults = from(new MockEnvironment().withProperty(BatchDefaults.WINDOW_MILLIS, "3")
                .withProperty(BatchDefaults.MAX_BATCH_SIZE, "250"));
        BatchMapping ann = annotationOf("explicitWindow");

        assertEquals(7, defaults.resolveWindowMillis(ann));
        assertEquals(250, defaults.resolveMaxBatchSize(ann), "未声明的属性仍应吃到全局值");
    }
}
