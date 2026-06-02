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

        assertNotNull(invoker1);
        assertNotNull(invoker2);
        assertEquals("Hello A", invoker1.invoke(new Object[]{"A"}));
        assertEquals("Hello B", invoker2.invoke(new Object[]{"B"}));
    }

    // ----- helper controller -----

    @SuppressWarnings("unused")
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