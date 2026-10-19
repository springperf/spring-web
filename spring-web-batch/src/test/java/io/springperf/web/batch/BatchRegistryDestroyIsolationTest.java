package io.springperf.web.batch;

import io.springperf.web.batch.queue.DisruptorQueue;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 回归 P2：{@code destroyComponent} 必须逐个 shutdown 队列。 修复前无 try-catch，某个队列停机抛异常会让后续队列永不关闭——Disruptor 消费者线程与 bizExecutor
 * 线程都是非 daemon，漏关任一个都会让 JVM 无法退出。
 */
class BatchRegistryDestroyIsolationTest {

    @Test
    void destroy_oneQueueShutdownFails_stillClosesOthers() throws Exception {
        BatchRegistry registry = new BatchRegistry();
        DisruptorQueue failing = mock(DisruptorQueue.class);
        doThrow(new RuntimeException("drain failed")).when(failing).shutdown();
        DisruptorQueue healthy = mock(DisruptorQueue.class);

        Field field = BatchRegistry.class.getDeclaredField("queues");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, DisruptorQueue> queues = (Map<String, DisruptorQueue>) field.get(registry);
        queues.put("bad", failing);
        queues.put("good", healthy);

        assertDoesNotThrow(() -> registry.destroyComponent(), "单个队列停机失败不得阻断其余队列的清理——否则泄漏非 daemon 线程");
        verify(failing).shutdown();
        verify(healthy).shutdown();
        assertTrue(queues.isEmpty(), "destroy 后队列表应被清空");
    }
}
