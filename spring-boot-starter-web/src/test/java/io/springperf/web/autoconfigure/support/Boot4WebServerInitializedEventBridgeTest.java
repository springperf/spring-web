package io.springperf.web.autoconfigure.support;

import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * {@link Boot4WebServerInitializedEventBridge} 并发安全测试。
 *
 * <p>桥接设计为"编译期零引用 SB4 类"（SB3 下不加载），本测试运行在 SB3 环境，
 * SB4 的 {@code WebServerInitializedEvent} 不存在，无法直接验证 defineClass 成功路径。
 * 但 C6 修复的并发约束可观测：并发发布必须一致地走到门卫异常（ClassNotFoundException），
 * 绝不因并发 {@code defineClass} 竞态抛 LinkageError。SB4 集成场景需在 SB4 工程验证。</p>
 */
class Boot4WebServerInitializedEventBridgeTest {

    @Test
    void publish_concurrent_inSb3_neverThrowsLinkageError() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        Boot4WebServerInitializedEventBridge.publish(
                                mock(NettyHttpServer.class), mock(ApplicationContext.class));
                        fail("SB3 环境（无 SB4 事件类）不应成功 publish");
                    } catch (Throwable t) {
                        // C6：并发 defineClass 竞态会抛 LinkageError；门卫路径应一致抛
                        // ClassNotFoundException（或其它非 LinkageError 异常）
                        assertFalse(t instanceof LinkageError,
                                "并发 publish 不得抛 LinkageError，实际: " + t);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdown();
        }
    }
}
