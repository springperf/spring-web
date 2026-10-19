package io.springperf.example.batch.perf;

import io.springperf.web.batch.annotation.BatchMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 批量 IO 消除效果的对照组。
 * <p>
 * 三个 endpoint 调用<b>同一个</b> {@link SimulatedIoRepository}、走<b>同一套</b> IO 逻辑，唯一差别是聚合方式：
 * </p>
 * <ul>
 * <li>{@code GET /perf/io/single/{id}} —— 每请求一次 round-trip（{@code findById}）</li>
 * <li>{@code GET /perf/io/batch-parallel/{id}} —— {@code consumerSize=8}，<b>合理并行度、无窗口</b></li>
 * <li>{@code GET /perf/io/batch-aggregate/{id}} —— {@code consumerSize=1}，<b>极端聚合</b>：用并行度换攒批能力</li>
 * <li>{@code GET /perf/io/batch-window/{id}} —— {@code consumerSize=8} +
 * {@code batchWindowMillis=5}，<b>窗口攒批</b>：并行度与攒批能力兼得</li>
 * </ul>
 * <p>
 * 因为 {@link SimulatedIoRepository#findById} 与 {@link SimulatedIoRepository#findByIds} 的等待时间严格一致， 各端测出的 QPS / IO 次数 / IO
 * 成本差异可以完全归因于「聚合方式」，不存在其他混淆变量。
 * </p>
 * <p>
 * <b>为什么需要多个 batch 端点</b>：{@link BufferingBatchHandler#flush} 用 {@code executor.execute()} 异步提交，IO 在 bizExecutor 而不在
 * Disruptor 消费者线程。消费者线程只在 {@code CallerRunsPolicy} 触发（bizExecutor 满员）时才被阻塞， 因此 {@code consumerSize} 与批大小是
 * <b>直接对冲</b>的——调大并行度会同时降低攒批能力。注意 {@code consumerSize} 的正当用途是复用多核，不应超过核数：这里用 8（保守合理值）， 而非为凑效果而超配。要兼得两者，得靠
 * {@code batchWindowMillis} 把刷出时机从「消费者追上空队列」改成「窗口到期」。
 * </p>
 */
@RestController
@RequestMapping("/perf/io")
public class PerfIoController {

    private final SimulatedIoRepository repository;

    public PerfIoController(SimulatedIoRepository repository) {
        this.repository = repository;
    }

    /**
     * baseline：单请求直达 IO。 返回值是普通 String，走 JSON 输出，不涉及 batch。
     */
    @GetMapping("/single/{id}")
    public String single(@PathVariable String id) {
        return repository.findById(id);
    }

    /**
     * 实验组 A（吞吐导向）：单请求方法体被 {@link BatchInvoker} 替换，只做入队并返回 {@link PerfIoRequest}； 真正的 IO 由 {@link #batchParallelFind}
     * 批量完成。原方法体不会执行。
     */
    @GetMapping("/batch-parallel/{id}")
    public PerfIoRequest batchParallel(@PathVariable String id) {
        return null;
    }

    /**
     * 实验组 B（聚合导向）：同上，但把 {@code consumerSize} 压到 1，使 bizExecutor 极易饱和、 频繁触发 {@code CallerRunsPolicy} 让 Disruptor
     * 消费者线程自己执行 IO——消费者一阻塞，RingBuffer 立刻积压，批大小随之冲高。
     */
    @GetMapping("/batch-aggregate/{id}")
    public PerfIoRequest batchAggregate(@PathVariable String id) {
        return null;
    }

    /**
     * 实验组 C（窗口攒批）：{@code consumerSize=8} 保持合理并行度，同时用 5ms 窗口把批大小交给配置保证， 而不是依赖 bizExecutor 偶然饱和。这是 「并行度与攒批能力兼得」的唯一路径。
     */
    @GetMapping("/batch-window/{id}")
    public PerfIoRequest batchWindow(@PathVariable String id) {
        return null;
    }

    /**
     * 批量 IO：把整批 id 折叠成一次 round-trip。
     * <p>
     * {@code maxBatchSize=100} 与并发档位对齐（并发 100 时可满批）；{@code consumerSize=8} 是贴近典型核数的合理并行度—— IO 是阻塞式的，批处理吞吐上限 ≈
     * {@code consumerSize / ioLatency × 平均批大小}，三者缺一不可。 {@code consumerSize} 用于复用多核，不应超过核数。
     * </p>
     */
    @BatchMapping(method = "batchParallel", maxBatchSize = 100, ringBufferSize = 8192, consumerSize = 8)
    public void batchParallelFind(List<PerfIoRequest> requests) {
        batchIo(requests);
    }

    /** 极端聚合配置：{@code consumerSize=1}，用并行度换攒批能力——仅作旋钮另一端的对照。 */
    @BatchMapping(method = "batchAggregate", maxBatchSize = 100, ringBufferSize = 8192, consumerSize = 1)
    public void batchAggregateFind(List<PerfIoRequest> requests) {
        batchIo(requests);
    }

    /**
     * 窗口攒批配置：{@code consumerSize=8}（复用多核，不超配）+ {@code batchWindowMillis=5}（批大小由窗口保证）。 窗口取 5ms：远小于 10ms 以上的 IO
     * 延迟，等待成本低于攒批收益。
     */
    @BatchMapping(method = "batchWindow", maxBatchSize = 100, ringBufferSize = 8192, batchWindowMillis = 5, consumerSize = 8)
    public void batchWindowFind(List<PerfIoRequest> requests) {
        batchIo(requests);
    }

    private void batchIo(List<PerfIoRequest> requests) {
        List<String> ids = new ArrayList<>(requests.size());
        for (PerfIoRequest req : requests) {
            ids.add(req.getId());
        }
        Map<String, String> rows = repository.findByIds(ids);
        for (PerfIoRequest req : requests) {
            req.setResult(rows.get(req.getId()));
        }
    }

    /** 暴露给测试读取计数器。 */
    public SimulatedIoRepository repository() {
        return repository;
    }
}
