package io.springperf.web.core.retval.resolver.async;

import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * {@code ListenableFuture} 到 {@link DeferredResult} 的适配器：注册回调，成功即 {@code setResult}，失败即
 * {@code setErrorResult}。
 * <p>
 * Spring 6 起 {@code ListenableFuture} 已标记弃用（推荐 {@link java.util.concurrent.CompletableFuture}），
 * 但本框架仍需支持以 {@code ListenableFuture} 为返回值的控制器方法。相关类型在本项目支持的 Spring 版本中均存在。
 * </p>
 */
@SuppressWarnings("deprecation")
public final class ListenableFutureAdapter {

    private ListenableFutureAdapter() {
    }

    public static boolean isAssignableFrom(Class<?> clazz) {
        return clazz != null && ListenableFuture.class.isAssignableFrom(clazz);
    }

    public static boolean isInstance(Object obj) {
        return obj instanceof ListenableFuture;
    }

    public static DeferredResult<Object> adapt(Object future) {
        DeferredResult<Object> result = new DeferredResult<>();
        @SuppressWarnings("unchecked")
        ListenableFuture<Object> listenable = (ListenableFuture<Object>) future;
        listenable.addCallback(new ListenableFutureCallback<>() {
            @Override
            public void onSuccess(Object value) {
                result.setResult(value);
            }

            @Override
            public void onFailure(Throwable ex) {
                result.setErrorResult(ex);
            }
        });
        return result;
    }
}
