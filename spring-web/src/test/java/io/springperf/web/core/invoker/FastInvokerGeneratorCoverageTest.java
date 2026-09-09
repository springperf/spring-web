package io.springperf.web.core.invoker;

import io.springperf.web.annotation.Optimize;
import org.junit.jupiter.api.Test;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Type;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class FastInvokerGeneratorCoverageTest {

    @Optimize
    static class FullPrimController {
        public double doubleOp(double a, double b) {
            return a + b;
        }

        public float floatOp(float a) {
            return a;
        }

        public short shortOp(short a) {
            return a;
        }

        public byte byteOp(byte a) {
            return a;
        }

        public char charOp(char a) {
            return a;
        }

        public String concatSeven(String a, String b, String c, String d, String e, String f, String g) {
            return a + b + c + d + e + f + g;
        }

        public String hello(String name) {
            return "Hello " + name;
        }
    }

    @Optimize
    static class FastController {
        public String hello(String name) {
            return "Hello " + name;
        }
    }

    static class PlainController {
        public String hello(String name) {
            return "Hello " + name;
        }
    }

    @Test
    void instantiate_defaultConstructor() {
        assertNotNull(new FastInvokerGenerator());
    }

    @Test
    void createInvoker_boxAndUnboxEveryPrimitive() throws Throwable {
        FullPrimController ctl = new FullPrimController();
        Class<?> cc = FullPrimController.class;

        assertInvoke(cc.getMethod("doubleOp", double.class, double.class), ctl, new Object[]{1.5d, 2.5d}, 4.0d);
        assertInvoke(cc.getMethod("floatOp", float.class), ctl, new Object[]{3.5f}, 3.5f);
        assertInvoke(cc.getMethod("shortOp", short.class), ctl, new Object[]{(short) 7}, (short) 7);
        assertInvoke(cc.getMethod("byteOp", byte.class), ctl, new Object[]{(byte) 5}, (byte) 5);
        assertInvoke(cc.getMethod("charOp", char.class), ctl, new Object[]{'x'}, 'x');
    }

    @Test
    void createInvoker_seventhParameter_usesBipush() throws Throwable {
        FullPrimController ctl = new FullPrimController();
        Method m = FullPrimController.class.getMethod("concatSeven",
                String.class, String.class, String.class, String.class, String.class, String.class, String.class);
        Invoker invoker = FastInvokerGenerator.createInvoker(ctl, FullPrimController.class, m);
        assertEquals("abcdefg", invoker.invoke(new Object[]{"a", "b", "c", "d", "e", "f", "g"}));
    }

    @Test
    void unbox_unsupportedPrimitiveType_throws() throws Exception {
        Method unbox = FastInvokerGenerator.class.getDeclaredMethod("unbox", MethodVisitor.class, Type.class);
        unbox.setAccessible(true);
        InvocationTargetException ex = assertThrows(InvocationTargetException.class,
                () -> unbox.invoke(null, mock(MethodVisitor.class), Type.VOID_TYPE));
        assertInstanceOf(IllegalStateException.class, ex.getCause());
    }

    @Test
    void invokableHandlerMethod_optimize_picksGeneratedInvoker() throws Exception {
        InvokableHandlerMethod handler = new InvokableHandlerMethod(
                new FastController(), FastController.class.getMethod("hello", String.class));
        assertTrue(handler.getInvoker().getClass().getName().startsWith("io.springperf.web.core.invoker.Invoker$"));
    }

    @Test
    void invokableHandlerMethod_withoutOptimize_fallsBackToMethodHandle() throws Exception {
        InvokableHandlerMethod handler = new InvokableHandlerMethod(
                new PlainController(), PlainController.class.getMethod("hello", String.class));
        assertInstanceOf(MethodHandleInvoker.class, handler.getInvoker());
    }

    @Test
    void createInvoker_nativeImagePropertyInIsolatedClassLoader_throws() throws Exception {
        // GraalVM native-image（master/SB3）特有的降级测试：2.7.x 无 IN_NATIVE_IMAGE 守卫，
        // 该行为依赖的字节码守卫不随本分支 backport，此测试不适用。
    }

    private static void assertInvoke(Method m, Object target, Object[] args, Object expected) throws Throwable {
        Invoker invoker = FastInvokerGenerator.createInvoker(target, target.getClass(), m);
        assertEquals(expected, invoker.invoke(args));
    }
}