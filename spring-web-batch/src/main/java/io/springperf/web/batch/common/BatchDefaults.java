package io.springperf.web.batch.common;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.context.ApplicationProperties;

/**
 * {@code springperf.batch.*} 全局默认值：为所有 {@link BatchMapping} 方法提供同一套兜底取值，免去逐个注解重复配置。
 * <p>
 * <b>优先级</b>：注解显式声明 &gt; {@code springperf.batch.*} &gt; 注解内置默认值。不配任何键时全部回落到
 * {@link #standard()}，行为与未引入本类前完全一致。
 * </p>
 * <p>
 * <b>已知边界</b>：Java 注解在运行期无法区分「未声明」与「显式写成内置默认值」——反射读到的都是同一个值，因此以
 * 「取值等于注解内置默认值」判定为未声明。给某队列单独 {@code @BatchMapping(batchWindowMillis = 5)} 想顶掉全局
 * {@code springperf.batch.window-millis=3} 是做不到的（两者取值相同）；改成任何不同的值（如 {@code 0} = 关闭窗口、
 * 或 {@code 8}）即可显式覆盖。
 * </p>
 * <p>
 * <b>生效时机</b>：只在启动期扫描 {@code @BatchMapping} 时读取一次；队列建立后改键不会重建队列（与
 * {@code pool.core-pool-size}、{@code server.netty.*} 等同为启动期键）。取值不合法时启动期直接失败，并指出键名与可选值。
 * </p>
 */
public final class BatchDefaults {

    /** 全局键前缀。 */
    public static final String PREFIX = "springperf.batch.";

    /** 攒批时间窗口（毫秒），对应 {@link BatchMapping#batchWindowMillis()}；{@code 0} = 全局关闭窗口。 */
    public static final String WINDOW_MILLIS = PREFIX + "window-millis";

    /** 单批最大请求数，对应 {@link BatchMapping#maxBatchSize()}。 */
    public static final String MAX_BATCH_SIZE = PREFIX + "max-batch-size";

    /** 业务线程池并发上限，对应 {@link BatchMapping#consumerSize()}；{@code -1} = CPU 核数。 */
    public static final String CONSUMER_SIZE = PREFIX + "consumer-size";

    /** 环形缓冲区容量，对应 {@link BatchMapping#ringBufferSize()}（非 2 的幂时由队列归一化并告警）。 */
    public static final String RING_BUFFER_SIZE = PREFIX + "ring-buffer-size";

    /** 消费者等待策略，对应 {@link BatchMapping#waitStrategy()}；接受 {@code BLOCKING} / {@code busy-spin} 等写法。 */
    public static final String WAIT_STRATEGY = PREFIX + "wait-strategy";

    /** 背压策略，对应 {@link BatchMapping#backpressure()}；接受 {@code BLOCK} / {@code drop} 等写法。 */
    public static final String BACKPRESSURE = PREFIX + "backpressure";

    /** 与注解内置默认值一致的缺省实例：未配置任何 {@code springperf.batch.*} 时使用。 */
    public static final BatchDefaults STANDARD = new BatchDefaults(AnnotationDefaults.WINDOW_MILLIS,
            AnnotationDefaults.MAX_BATCH_SIZE, AnnotationDefaults.CONSUMER_SIZE, AnnotationDefaults.RING_BUFFER_SIZE,
            AnnotationDefaults.WAIT_STRATEGY, AnnotationDefaults.BACKPRESSURE, Collections.emptyList());

    private final int windowMillis;

    private final int maxBatchSize;

    private final int consumerSize;

    private final int ringBufferSize;

    private final BatchMapping.WaitStrategy waitStrategy;

    private final BatchMapping.Backpressure backpressure;

    /** 真正从配置读到的键（按声明顺序）；空表示全部用内置默认值。仅用于启动日志留痕。 */
    private final List<String> configuredKeys;

    private BatchDefaults(int windowMillis, int maxBatchSize, int consumerSize, int ringBufferSize,
            BatchMapping.WaitStrategy waitStrategy, BatchMapping.Backpressure backpressure, List<String> configuredKeys) {
        this.windowMillis = windowMillis;
        this.maxBatchSize = maxBatchSize;
        this.consumerSize = consumerSize;
        this.ringBufferSize = ringBufferSize;
        this.waitStrategy = waitStrategy;
        this.backpressure = backpressure;
        this.configuredKeys = configuredKeys;
    }

    /** 与注解默认值一致的实例（无 Spring {@code Environment} 时，如直接构造 {@link BatchScanner} 的单元测试）。 */
    public static BatchDefaults standard() {
        return STANDARD;
    }

    /**
     * 从框架配置读取全局默认值。
     *
     * @param props
     *            框架配置访问器；{@code null}（未接入 Spring Environment 的场景）等价于 {@link #standard()}
     * @return 解析后的全局默认值，缺省项回落到注解内置默认值
     * @throws IllegalArgumentException
     *             键存在但取值无法解析（非整数 / 不是合法枚举名）
     */
    public static BatchDefaults fromProperties(ApplicationProperties props) {
        if (props == null) {
            return STANDARD;
        }
        List<String> configured = new ArrayList<>(6);
        int window = resolveInt(props, WINDOW_MILLIS, AnnotationDefaults.WINDOW_MILLIS, configured);
        int maxBatch = resolveInt(props, MAX_BATCH_SIZE, AnnotationDefaults.MAX_BATCH_SIZE, configured);
        int consumer = resolveInt(props, CONSUMER_SIZE, AnnotationDefaults.CONSUMER_SIZE, configured);
        int ringBuffer = resolveInt(props, RING_BUFFER_SIZE, AnnotationDefaults.RING_BUFFER_SIZE, configured);
        BatchMapping.WaitStrategy waitStrategy = resolveEnum(props, WAIT_STRATEGY, AnnotationDefaults.WAIT_STRATEGY,
                BatchMapping.WaitStrategy.class, configured);
        BatchMapping.Backpressure backpressure = resolveEnum(props, BACKPRESSURE, AnnotationDefaults.BACKPRESSURE,
                BatchMapping.Backpressure.class, configured);
        return new BatchDefaults(window, maxBatch, consumer, ringBuffer, waitStrategy, backpressure,
                Collections.unmodifiableList(new ArrayList<>(configured)));
    }

    // ------------------------------------------------------------------
    // Resolution（注解未显式声明时用全局值）
    // ------------------------------------------------------------------

    public int resolveRingBufferSize(BatchMapping ann) {
        return pick(ann.ringBufferSize(), AnnotationDefaults.RING_BUFFER_SIZE, ringBufferSize);
    }

    public BatchMapping.WaitStrategy resolveWaitStrategy(BatchMapping ann) {
        return pick(ann.waitStrategy(), AnnotationDefaults.WAIT_STRATEGY, waitStrategy);
    }

    public BatchMapping.Backpressure resolveBackpressure(BatchMapping ann) {
        return pick(ann.backpressure(), AnnotationDefaults.BACKPRESSURE, backpressure);
    }

    public int resolveMaxBatchSize(BatchMapping ann) {
        return pick(ann.maxBatchSize(), AnnotationDefaults.MAX_BATCH_SIZE, maxBatchSize);
    }

    public int resolveWindowMillis(BatchMapping ann) {
        return pick(ann.batchWindowMillis(), AnnotationDefaults.WINDOW_MILLIS, windowMillis);
    }

    /**
     * 注解的 {@code consumerSize} 默认 {@code -1}（= CPU 核数），与「未声明」同义；全局键缺省同为 {@code -1}， 因此两者任一为
     * {@code -1} 都回落到「CPU 核数」这一层语义（由 {@link BatchScanner} 最终解析）。
     */
    public int resolveConsumerSize(BatchMapping ann) {
        return pick(ann.consumerSize(), AnnotationDefaults.CONSUMER_SIZE, consumerSize);
    }

    private static <T> T pick(T annotationValue, T annotationDefault, T globalValue) {
        return annotationDefault.equals(annotationValue) ? globalValue : annotationValue;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public int windowMillis() {
        return windowMillis;
    }

    public int maxBatchSize() {
        return maxBatchSize;
    }

    public int consumerSize() {
        return consumerSize;
    }

    public int ringBufferSize() {
        return ringBufferSize;
    }

    public BatchMapping.WaitStrategy waitStrategy() {
        return waitStrategy;
    }

    public BatchMapping.Backpressure backpressure() {
        return backpressure;
    }

    /**
     * 实际从配置读到的键；空列表表示全部使用注解内置默认值。
     * <p>
     * 返回副本：字段本身在构造后只读，但没有必要把内部列表身份暴露给调用方（启动日志一次性使用，拷贝成本可忽略）。
     * </p>
     */
    public List<String> configuredKeys() {
        return Collections.unmodifiableList(new ArrayList<>(configuredKeys));
    }

    /** 启动日志用的一行摘要。 */
    public String describe() {
        return "windowMillis=" + windowMillis + ", maxBatchSize=" + maxBatchSize + ", consumerSize=" + consumerSize
                + ", ringBufferSize=" + ringBufferSize + ", waitStrategy=" + waitStrategy + ", backpressure="
                + backpressure;
    }

    @Override
    public String toString() {
        return "BatchDefaults{" + describe() + ", configuredKeys=" + configuredKeys + "}";
    }

    // ------------------------------------------------------------------
    // Property parsing
    // ------------------------------------------------------------------

    private static int resolveInt(ApplicationProperties props, String key, int fallback, List<String> configured) {
        String raw = props.get(key, null);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        configured.add(key);
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid value for [" + key + "]: '" + raw + "' is not an integer", e);
        }
    }

    private static <E extends Enum<E>> E resolveEnum(ApplicationProperties props, String key, E fallback,
            Class<E> type, List<String> configured) {
        String raw = props.get(key, null);
        if (raw == null || raw.trim().isEmpty()) {
            return fallback;
        }
        configured.add(key);
        // 与 ErrorResponseConfig 同一归一化口径：忽略大小写，连字符视为下划线（busy-spin / BUSY-SPIN 均可）
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid value for [" + key + "]: '" + raw + "'; expected one of "
                    + Arrays.toString(type.getEnumConstants()), e);
        }
    }

    /**
     * 注解内置默认值，反射读取而非硬编码：注解默认值调整后（例如窗口 5ms → 3ms）这里会自动跟随，不会出现「全局未配置却
     * 用到旧默认值」的静默漂移。
     */
    private static final class AnnotationDefaults {

        static final int RING_BUFFER_SIZE = (Integer) defaultValue("ringBufferSize");

        static final BatchMapping.WaitStrategy WAIT_STRATEGY = (BatchMapping.WaitStrategy) defaultValue("waitStrategy");

        static final BatchMapping.Backpressure BACKPRESSURE = (BatchMapping.Backpressure) defaultValue("backpressure");

        static final int MAX_BATCH_SIZE = (Integer) defaultValue("maxBatchSize");

        static final int WINDOW_MILLIS = (Integer) defaultValue("batchWindowMillis");

        static final int CONSUMER_SIZE = (Integer) defaultValue("consumerSize");

        private static Object defaultValue(String attribute) {
            try {
                Method method = BatchMapping.class.getMethod(attribute);
                Object value = method.getDefaultValue();
                if (value == null) {
                    throw new IllegalStateException("@BatchMapping#" + attribute + " 必须声明默认值");
                }
                return value;
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("@BatchMapping 缺少属性 " + attribute, e);
            }
        }
    }
}
