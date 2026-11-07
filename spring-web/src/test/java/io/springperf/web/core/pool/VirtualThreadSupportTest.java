package io.springperf.web.core.pool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadFactory;

import org.junit.jupiter.api.Test;

/**
 * {@link VirtualThreadSupport}：能力探测口径与虚拟线程工厂（两个 JDK 分支都必须有断言）。
 */
class VirtualThreadSupportTest {

    @Test
    void isAvailable_matchesJdkCapability() {
        assertEquals(Runtime.version().feature() >= 21, VirtualThreadSupport.isAvailable());
    }

    @Test
    void newThreadFactory_matchesJdkCapability() throws Exception {
        if (Runtime.version().feature() >= 21) {
            ThreadFactory factory = VirtualThreadSupport.newThreadFactory("vt-test-");
            Thread thread = factory.newThread(() -> {
            });
            assertTrue(thread.getName().startsWith("vt-test-"), "线程名应带前缀，实际: " + thread.getName());
            Method isVirtual = Thread.class.getMethod("isVirtual");
            assertTrue((boolean) isVirtual.invoke(thread), "工厂应创建虚拟线程");
        } else {
            // 不支持路径必须明确失败，而不是静默返回平台线程工厂
            assertThrows(IllegalStateException.class, () -> VirtualThreadSupport.newThreadFactory("vt-test-"));
        }
    }
}
