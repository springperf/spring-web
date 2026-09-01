package io.springperf.web.core.invoker;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class FastInvokerGeneratorTest {

    private final FastController controller = new FastController();

    // ----- String method -----

    @Test
    void createInvoker_stringMethod_returnsCorrectResult() throws Throwable {
        Method method = FastController.class.getMethod("hello", String.class);
        Invoker invoker = FastInvokerGenerator.createInvoker(controller, FastController.class, method);

        Object result = invoker.invoke(new Object[]{"World"});

        assertEquals("Hello World", result);
    }

    // ----- void method -----

    @Test
    void createInvoker_voidMethod_returnsNull() throws Throwable {
        Method method = FastController.class.getMethod("voidMethod");
        Invoker invoker = FastInvokerGenerator.createInvoker(controller, FastController.class, method);

        Object result = invoker.invoke(new Object[0]);

        assertNull(result);
        assertTrue(controller.voidCalled);
    }

    // ----- primitive return boxing -----

    @Test
    void createInvoker_intReturn_boxesToInteger() throws Throwable {
        Method method = FastController.class.getMethod("add", int.class, int.class);
        Invoker invoker = FastInvokerGenerator.createInvoker(controller, FastController.class, method);

        Object result = invoker.invoke(new Object[]{3, 4});

        assertTrue(result instanceof Integer);
        assertEquals(7, result);
    }

    @Test
    void createInvoker_booleanReturn_boxesToBoolean() throws Throwable {
        Method method = FastController.class.getMethod("isActive");
        Invoker invoker = FastInvokerGenerator.createInvoker(controller, FastController.class, method);

        Object result = invoker.invoke(new Object[0]);

        assertTrue(result instanceof Boolean);
        assertTrue((Boolean) result);
    }

    @Test
    void createInvoker_longReturn_boxesToLong() throws Throwable {
        Method method = FastController.class.getMethod("multiply", long.class, int.class);
        Invoker invoker = FastInvokerGenerator.createInvoker(controller, FastController.class, method);

        Object result = invoker.invoke(new Object[]{10L, 5});

        assertTrue(result instanceof Long);
        assertEquals(50L, result);
    }

    // ----- multiple params and different types -----

    @Test
    void createInvoker_multipleMixedParams_works() throws Throwable {
        Method method = FastController.class.getMethod("concat", String.class, int.class, boolean.class);
        Invoker invoker = FastInvokerGenerator.createInvoker(controller, FastController.class, method);

        Object result = invoker.invoke(new Object[]{"test", 42, true});

        assertEquals("test42true", result);
    }

    // ----- class caching -----

    @Test
    void createInvoker_sameMethod_returnsDifferentInstance() throws Throwable {
        Method method = FastController.class.getMethod("hello", String.class);
        FastController another = new FastController();

        Invoker invoker1 = FastInvokerGenerator.createInvoker(controller, FastController.class, method);
        Invoker invoker2 = FastInvokerGenerator.createInvoker(another, FastController.class, method);

        assertNotSame(invoker1, invoker2, "同一方法应复用生成的类，但每次 new 出独立实例");
        assertEquals("Hello A", invoker1.invoke(new Object[]{"A"}));
        assertEquals("Hello B", invoker2.invoke(new Object[]{"B"}));
    }

    // ----- GraalVM native-image 降级 -----

    /**
     * native-image 场景下禁止运行时生成字节码（FastInvokerGenerator 抛异常），
     * InvokableHandlerMethod 应降级为 MethodHandleInvoker。
     * <p>由于 {@code IN_NATIVE_IMAGE} 是类加载期求值的 static final，
     * 需 fork 子 JVM 并注入 {@code org.graalvm.nativeimage.imagecode} 系统属性。
     */
    @Test
    void createInvoker_inNativeImage_degradesToMethodHandleInvoker() throws Exception {
        String javaBin = System.getProperty("java.home") + java.io.File.separator + "bin"
                + java.io.File.separator + (isWindows() ? "java.exe" : "java");
        String classpath = System.getProperty("java.class.path");
        Process p = new ProcessBuilder(javaBin,
                "-Dorg.graalvm.nativeimage.imagecode=runtime",
                "-cp", classpath,
                NativeImageChildMain.class.getName())
                .redirectErrorStream(true)
                .start();
        String output = org.springframework.util.StreamUtils.copyToString(
                p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = p.waitFor();

        assertEquals(0, exit, "native-image 降级断言失败（exit=" + exit + "）：" + output);
        assertTrue(output.contains("NATIVE_DEGRADATION_OK"), "缺少降级标记：" + output);
        assertFalse(output.contains("EXPECTED_EXCEPTION_NOT_THROWN"),
                "native 下 FastInvokerGenerator 不应成功生成字节码：" + output);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 子 JVM 入口：验证 native 环境下生成字节码被拒且降级为 MethodHandleInvoker */
    @SuppressWarnings("unused")
    static class NativeImageChildMain {
        public static void main(String[] args) throws Throwable {
            Method method = FastController.class.getMethod("hello", String.class);
            // 1) 直接调用必须抛 UnsupportedOperationException
            try {
                FastInvokerGenerator.createInvoker(new FastController(), FastController.class, method);
                System.out.println("EXPECTED_EXCEPTION_NOT_THROWN");
                System.exit(2);
            } catch (UnsupportedOperationException expected) {
                // 预期行为：拒绝生成字节码
            }
            // 2) InvokableHandlerMethod 降级为 MethodHandleInvoker（@Optimize 也不走字节码）
            InvokableHandlerMethod handler = new InvokableHandlerMethod(new FastController(), method);
            if (handler.getInvoker() instanceof MethodHandleInvoker) {
                System.out.println("NATIVE_DEGRADATION_OK invoker=" + handler.getInvoker().getClass().getSimpleName());
                System.exit(0);
            }
            System.out.println("NOT_METHOD_HANDLE invoker=" + handler.getInvoker().getClass().getName());
            System.exit(1);
        }
    }

    // ----- helper controller -----

    @SuppressWarnings("unused")
    @io.springperf.web.annotation.Optimize
    static class FastController {
        boolean voidCalled;

        public String hello(String name) {
            return "Hello " + name;
        }

        public void voidMethod() {
            this.voidCalled = true;
        }

        public int add(int a, int b) {
            return a + b;
        }

        public boolean isActive() {
            return true;
        }

        public long multiply(long a, int b) {
            return a * b;
        }

        public String concat(String s, int i, boolean b) {
            return s + i + b;
        }
    }
}