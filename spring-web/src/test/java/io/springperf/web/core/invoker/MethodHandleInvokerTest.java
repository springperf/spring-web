package io.springperf.web.core.invoker;

import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import static org.junit.jupiter.api.Assertions.*;

class MethodHandleInvokerTest {

    @Test
    void invoke_stringArg_returnsResult() throws Throwable {
        MethodHandle handle = MethodHandles.lookup().unreflect(
                getClass().getMethod("hello", String.class)).bindTo(this);
        MethodHandleInvoker invoker = new MethodHandleInvoker(handle);

        Object result = invoker.invoke(new Object[]{"World"});

        assertEquals("Hello World", result);
    }

    @Test
    void invoke_noArgs_returnsResult() throws Throwable {
        MethodHandle handle = MethodHandles.lookup().unreflect(
                getClass().getMethod("ping")).bindTo(this);
        MethodHandleInvoker invoker = new MethodHandleInvoker(handle);

        Object result = invoker.invoke(new Object[0]);

        assertEquals("pong", result);
    }

    @Test
    void invoke_nullArgs_treatedAsEmpty() throws Throwable {
        MethodHandle handle = MethodHandles.lookup().unreflect(
                getClass().getMethod("ping")).bindTo(this);
        MethodHandleInvoker invoker = new MethodHandleInvoker(handle);

        // MethodHandle.invokeWithArguments treats null as empty array
        Object result = invoker.invoke(null);
        assertEquals("pong", result);
    }

    @Test
    void invoke_methodThrows_propagatesException() throws Throwable {
        MethodHandle handle = MethodHandles.lookup().unreflect(
                getClass().getMethod("fail")).bindTo(this);
        MethodHandleInvoker invoker = new MethodHandleInvoker(handle);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> invoker.invoke(new Object[0]));
        assertTrue(ex.getMessage().contains("boom"));
    }

    @Test
    void invoke_intArg_returnsInt() throws Throwable {
        MethodHandle handle = MethodHandles.lookup().unreflect(
                getClass().getMethod("add", int.class, int.class)).bindTo(this);
        MethodHandleInvoker invoker = new MethodHandleInvoker(handle);

        Object result = invoker.invoke(new Object[]{3, 4});

        assertEquals(7, result);
    }

    @SuppressWarnings("unused")
    public String hello(String name) {
        return "Hello " + name;
    }

    @SuppressWarnings("unused")
    public String ping() {
        return "pong";
    }

    @SuppressWarnings("unused")
    public void fail() {
        throw new IllegalStateException("intentional boom");
    }

    @SuppressWarnings("unused")
    public int add(int a, int b) {
        return a + b;
    }
}