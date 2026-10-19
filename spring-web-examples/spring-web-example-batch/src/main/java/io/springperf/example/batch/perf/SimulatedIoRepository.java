package io.springperf.example.batch.perf;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模拟「一次 DB round-trip」的 Repository，用于量化 batch 聚合对 IO 的消除效果。
 * <p>
 * 设计要点：
 * </p>
 * <ul>
 * <li><b>延迟可控</b>：{@code ioLatencyNanos} 可参数化，便于扫描「IO 越慢收益越大」的曲线并定位交叉点。</li>
 * <li><b>单次与批量同延迟</b>：{@link #findById} 与 {@link #findByIds} 的等待时间完全一致（都与条数无关）。 这正是真实 DB
 * 的模型——{@code SELECT ... WHERE id IN (...)} 的耗时主要由 round-trip 决定，而非结果行数。 二者延迟一致，对照组才成立：测出的差异只可能来自「聚合与否」。</li>
 * <li><b>可计数</b>：{@code ioCount} 记录真实 round-trip 次数，是「IO 被消除」最直接的硬证据；{@code ioNanos} 记录累计 IO 等待时间， 用于计算「每请求 IO
 * 成本」——该指标不受客户端/HTTP 瓶颈影响，比 QPS 更能反映 IO 消除本身。</li>
 * </ul>
 * <p>
 * 之所以不用真实 SQLite：其 JDBC 开销、文件锁与不可控的查询延迟会引入与 batch 无关的噪声， 且无法精确计数 round-trip。本类是接口形态，后续要换真实 DB 只需替换实现，测试框架不变。
 * </p>
 */
@Component
public class SimulatedIoRepository {

    private final long ioLatencyNanos;

    private final AtomicLong ioCount = new AtomicLong();
    private final AtomicLong ioNanos = new AtomicLong();

    public SimulatedIoRepository() {
        this(0L);
    }

    public SimulatedIoRepository(long ioLatencyMillis) {
        this.ioLatencyNanos = ioLatencyMillis <= 0 ? 0L : ioLatencyMillis * 1_000_000L;
    }

    /** 单条查询：一次 round-trip。 */
    public String findById(String id) {
        awaitRoundTrip();
        return "row:" + id;
    }

    /**
     * 批量查询：同样一次 round-trip，一次拿回整批。 与 {@link #findById} 的等待时间严格一致——这是整个对照实验的基石。
     */
    public Map<String, String> findByIds(List<String> ids) {
        awaitRoundTrip();
        Map<String, String> rows = new HashMap<>(Math.max(4, (int) (ids.size() / 0.75f) + 1));
        for (String id : ids) {
            rows.put(id, "row:" + id);
        }
        return rows;
    }

    /** 批量查询的 List 重载：与 Map 重载同语义，便于调用方少写一次转换。 */
    public List<String> findByIdList(List<String> ids) {
        awaitRoundTrip();
        List<String> rows = new ArrayList<>(ids.size());
        for (String id : ids) {
            rows.add("row:" + id);
        }
        return rows;
    }

    private void awaitRoundTrip() {
        ioCount.incrementAndGet();
        if (ioLatencyNanos <= 0) {
            return;
        }
        long start = System.nanoTime();
        // parkNanos 而非 Thread.sleep：亚毫秒级精度，且不依赖 sleep 的 1ms 粒度与调度抖动
        java.util.concurrent.locks.LockSupport.parkNanos(ioLatencyNanos);
        ioNanos.addAndGet(System.nanoTime() - start);
    }

    public long ioCount() {
        return ioCount.get();
    }

    /** 累计 IO 等待时间（纳秒），含 park 的轻微过冲。 */
    public long ioNanos() {
        return ioNanos.get();
    }

    public void reset() {
        ioCount.set(0);
        ioNanos.set(0);
    }

    /** 每请求 IO 成本（纳秒）= 累计 IO 等待 / 请求数。延迟为 0 时返回 0。 */
    public double ioCostPerRequestNanos(long requestCount) {
        return requestCount == 0 ? 0.0 : (double) ioNanos.get() / requestCount;
    }
}
