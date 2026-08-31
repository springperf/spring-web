package io.springperf.web.batch;

import io.springperf.web.batch.annotation.BatchMapping;
import io.springperf.web.batch.common.BatchHandlerRegistration;
import io.springperf.web.batch.common.BatchRequest;
import io.springperf.web.batch.common.BatchRequestMetaData;
import io.springperf.web.batch.queue.DisruptorQueue;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.core.pool.BizPoolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 回归 P2 并发组 #7：同 queueName 重复安装时，新建的 DisruptorQueue 必须先 shutdown
 * 再抛异常，否则其 Disruptor 线程（非 daemon）泄漏、挂住测试 JVM。
 * <p>修复前 {@code putIfAbsent} 冲突直接抛 {@code IllegalStateException}，新建队列的
 * 线程无人回收。</p>
 */
class BatchRegistryInstallConflictTest {

    private static final String QUEUE = "test-queue";

    private static final class DummyBatchRequest extends BatchRequest<Object> {
    }

    private static final class DummyBatchBean {
        @SuppressWarnings("unused")
        public void handle(DummyBatchRequest req) {
        }
    }

    @Test
    void duplicateQueueName_throwsAndShutsDownNewQueue() throws Exception {
        BatchRegistry registry = new BatchRegistry();
        // install 尾部会经 webContext 查询 BizPoolRegistry，mock 掉避免依赖完整容器生命周期
        WebContext webContext = mock(WebContext.class);
        when(webContext.getWebComponent(BizPoolRegistry.class)).thenReturn(null);
        registry.initWithWebContext(webContext);
        Method install = BatchRegistry.class.getDeclaredMethod("install", BatchHandlerRegistration.class);
        install.setAccessible(true);

        try {
            // 首次安装成功：Disruptor 线程启动
            install.invoke(registry, newRegistration());
            awaitDisruptorThreadCount(QUEUE, 1);

            // 二次同名安装：抛 IllegalStateException，且新建队列的线程被 shutdown
            InvocationTargetException ex =
                    assertThrows(InvocationTargetException.class, () -> install.invoke(registry, newRegistration()));
            assertTrue(ex.getCause() instanceof IllegalStateException, "冲突应抛 IllegalStateException");

            // 冲突路径新建的 queue 已被 shutdown，其 Disruptor 线程终止，只剩首次那个
            awaitDisruptorThreadCount(QUEUE, 1);
        } finally {
            // 无论如何都清理已安装的队列，避免非 daemon Disruptor 线程泄漏挂住测试 JVM
            shutdownQueues(registry);
        }
    }

    private static BatchHandlerRegistration newRegistration() throws Exception {
        Method m = DummyBatchBean.class.getDeclaredMethod("handle", DummyBatchRequest.class);
        HandlerMethod handlerMethod = new HandlerMethod(new DummyBatchBean(), m);
        PathMappingContext singleCtx = new PathMappingContext(handlerMethod, Collections.emptyList(), "/dummy");
        Constructor<?> ctor = String.class.getConstructor();
        BatchRequestMetaData meta = new BatchRequestMetaData(
                m, DummyBatchBean.class, DummyBatchRequest.class, QUEUE,
                64, BatchMapping.WaitStrategy.BLOCKING, BatchMapping.Backpressure.BLOCK,
                ctor, 16, 1);
        return new BatchHandlerRegistration(new DummyBatchBean(), singleCtx, meta);
    }

    private static void awaitDisruptorThreadCount(String queueName, int expected) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            long alive = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.isAlive() && t.getName().startsWith("batch-disruptor-" + queueName))
                    .count();
            if (alive == expected) return;
            Thread.sleep(50);
        }
        long alive = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.isAlive() && t.getName().startsWith("batch-disruptor-" + queueName))
                .count();
        throw new AssertionError("期望 batch-disruptor-" + queueName + " 存活线程数 = " + expected + "，实际 = " + alive);
    }

    private static void shutdownQueues(BatchRegistry registry) throws Exception {
        Field field = BatchRegistry.class.getDeclaredField("queues");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, DisruptorQueue> queues = (Map<String, DisruptorQueue>) field.get(registry);
        for (DisruptorQueue q : queues.values()) {
            q.shutdown();
        }
        queues.clear();
    }
}
