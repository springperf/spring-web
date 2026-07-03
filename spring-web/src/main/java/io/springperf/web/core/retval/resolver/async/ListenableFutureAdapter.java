package io.springperf.web.core.retval.resolver.async;

import org.springframework.web.context.request.async.DeferredResult;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Adapter for {@code ListenableFuture} which was removed in Spring Framework 7.0+ (SB 4.x).
 * Uses reflection to maintain cross-version compatibility.
 */
public class ListenableFutureAdapter {

    private static final boolean LISTENABLE_FUTURE_AVAILABLE;
    private static final Class<?> LISTENABLE_FUTURE_CLASS;
    private static final Class<?> LISTENABLE_FUTURE_CALLBACK_CLASS;

    static {
        boolean available = false;
        Class<?> futureClass = null;
        Class<?> callbackClass = null;
        try {
            futureClass = Class.forName("org.springframework.util.concurrent.ListenableFuture");
            callbackClass = Class.forName("org.springframework.util.concurrent.ListenableFutureCallback");
            available = true;
        } catch (ClassNotFoundException e) {
            // ListenableFuture was removed in Spring Framework 7.0+
        }
        LISTENABLE_FUTURE_AVAILABLE = available;
        LISTENABLE_FUTURE_CLASS = futureClass;
        LISTENABLE_FUTURE_CALLBACK_CLASS = callbackClass;
    }

    public static boolean isAvailable() {
        return LISTENABLE_FUTURE_AVAILABLE;
    }

    public static boolean isAssignableFrom(Class<?> clazz) {
        if (!LISTENABLE_FUTURE_AVAILABLE || LISTENABLE_FUTURE_CLASS == null) {
            return false;
        }
        return LISTENABLE_FUTURE_CLASS.isAssignableFrom(clazz);
    }

    public static boolean isInstance(Object obj) {
        if (!LISTENABLE_FUTURE_AVAILABLE || LISTENABLE_FUTURE_CLASS == null) {
            return false;
        }
        return LISTENABLE_FUTURE_CLASS.isInstance(obj);
    }

    public static DeferredResult<Object> adapt(Object future) {
        DeferredResult<Object> result = new DeferredResult<>();
        try {
            Object callback = Proxy.newProxyInstance(
                    ListenableFutureAdapter.class.getClassLoader(),
                    new Class<?>[]{LISTENABLE_FUTURE_CALLBACK_CLASS},
                    (proxy, method, args) -> {
                        String methodName = method.getName();
                        if ("onSuccess".equals(methodName)) {
                            result.setResult(args.length > 0 ? args[0] : null);
                        } else if ("onFailure".equals(methodName)) {
                            result.setErrorResult(args[0]);
                        }
                        return null;
                    }
            );
            Method addCallback = future.getClass().getMethod("addCallback", LISTENABLE_FUTURE_CALLBACK_CLASS);
            addCallback.invoke(future, callback);
        } catch (Exception e) {
            throw new RuntimeException("Failed to adapt ListenableFuture", e);
        }
        return result;
    }
}
