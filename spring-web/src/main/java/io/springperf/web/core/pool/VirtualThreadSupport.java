package io.springperf.web.core.pool;

import java.lang.reflect.Method;
import java.util.concurrent.ThreadFactory;

/**
 * 虚拟线程能力探测与创建（编译目标 JDK 17，运行时通过反射调用 {@code Thread.ofVirtual}）。
 *
 * <p>全框架唯一入口：{@link BizPoolRegistry} 的 default 业务池、batch 模块的批量方法执行池
 * 都经此创建虚拟线程，保证探测口径与线程命名一致。</p>
 */
public final class VirtualThreadSupport {

    private static final boolean AVAILABLE = detect();

    private VirtualThreadSupport() {
    }

    /** 运行时是否支持虚拟线程（JDK 21+ 存在 {@code Thread.ofVirtual}）。 */
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    private static boolean detect() {
        try {
            Thread.class.getMethod("ofVirtual");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * 创建以虚拟线程实现的 {@link ThreadFactory}（线程名前缀 {@code namePrefix}）。
     *
     * <p>在 JDK 21+ 上调用 {@code Thread.ofVirtual().name(namePrefix).factory()}；通过公开接口
     * {@code java.lang.Thread$Builder$OfVirtual} 反射，避免模块系统限制。</p>
     *
     * @throws IllegalStateException 当前 JVM 不支持虚拟线程（调用前应先用 {@link #isAvailable()} 判断）
     */
    public static ThreadFactory newThreadFactory(String namePrefix) {
        try {
            Object ofVirtual = Thread.class.getMethod("ofVirtual").invoke(null);
            // Thread.Builder.OfVirtual 是公开接口，方法可访问
            Class<?> ofVirtualIface = Class.forName("java.lang.Thread$Builder$OfVirtual");
            Method nameMethod = ofVirtualIface.getMethod("name", String.class);
            Object named = nameMethod.invoke(ofVirtual, namePrefix);
            Method factoryMethod = ofVirtualIface.getMethod("factory");
            return (ThreadFactory) factoryMethod.invoke(named);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Virtual threads require JDK 21+ (Thread.ofVirtual is unavailable): " + e, e);
        }
    }
}
