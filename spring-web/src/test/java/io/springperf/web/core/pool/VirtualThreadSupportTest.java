package io.springperf.web.core.pool;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link VirtualThreadSupport}：能力探测口径与虚拟线程工厂（虚拟线程断言仅在 JDK 21+ 执行）。
 */
class VirtualThreadSupportTest {

    @Test
    void isAvailable_matchesJdkCapability() {
        assertEquals(Runtime.version().feature() >= 21, VirtualThreadSupport.isAvailable());
    }

    @Test
    void newThreadFactory_producesVirtualThreadsOnJdk21() throws Exception {
        assumeTrue(Runtime.version().feature() >= 21, "虚拟线程需要 JDK 21+");
        ThreadFactory factory = VirtualThreadSupport.newThreadFactory("vt-test-");
        Thread t = factory.newThread(() -> { });
        assertTrue(t.getName().startsWith("vt-test-"), "线程名应带前缀，实际: " + t.getName());
        Method isVirtual = Thread.class.getMethod("isVirtual");
        assertTrue((boolean) isVirtual.invoke(t), "工厂应创建虚拟线程");
    }

    @Test
    void newThreadFactory_throwsWhenUnsupported() {
        assumeTrue(Runtime.version().feature() < 21, "仅在 JDK < 21 上验证不支持路径");
        assertThrows(IllegalStateException.class, () -> VirtualThreadSupport.newThreadFactory("vt-test-"));
    }
}
