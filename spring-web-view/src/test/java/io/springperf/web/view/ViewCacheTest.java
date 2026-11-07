package io.springperf.web.view;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewCacheTest {

    private static final View VIEW = (model, req, resp) -> {
    };

    private static final int MAX_SIZE = 1024;

    @Test
    void resolve_cachesPositiveResult() throws Exception {
        ViewCache cache = new ViewCache(true);
        AtomicInteger loads = new AtomicInteger();
        ViewCache.Loader loader = countingLoader(loads, VIEW);

        assertSame(VIEW, cache.resolve("home", loader));
        assertSame(VIEW, cache.resolve("home", loader));
        assertEquals(1, loads.get());
    }

    @Test
    void resolve_cachesNegativeResult() throws Exception {
        ViewCache cache = new ViewCache(true);
        AtomicInteger loads = new AtomicInteger();
        ViewCache.Loader loader = key -> {
            loads.incrementAndGet();
            return null;
        };

        assertNull(cache.resolve("missing", loader));
        assertNull(cache.resolve("missing", loader));
        assertEquals(1, loads.get(), "模板不存在的负结果也应缓存");
    }

    @Test
    void resolve_cacheDisabled_loadsEveryTime() throws Exception {
        ViewCache cache = new ViewCache(false);
        AtomicInteger loads = new AtomicInteger();
        ViewCache.Loader loader = countingLoader(loads, VIEW);

        assertSame(VIEW, cache.resolve("home", loader));
        assertSame(VIEW, cache.resolve("home", loader));
        assertEquals(2, loads.get(), "cache=false 必须完全旁路缓存");
    }

    @Test
    void resolve_concurrentSameKey_loadsOnce() throws Exception {
        ViewCache cache = new ViewCache(true);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch loaderEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ViewCache.Loader loader = key -> {
            loads.incrementAndGet();
            loaderEntered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS), "加载线程不应被饿死");
            return VIEW;
        };

        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    assertSame(VIEW, cache.resolve("home", loader));
                } catch (Throwable ex) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(loaderEntered.await(5, TimeUnit.SECONDS), "至少一个线程应进入加载");
        // 放行前留出窗口，让其余线程都走到占位判断；无单飞时它们会各自触发 loader
        Thread.sleep(30);
        release.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(0, failures.get());
        assertEquals(1, loads.get(), "同一 key 并发解析只应加载一次");
    }

    @Test
    void resolve_loaderFailure_isNotCached() throws Exception {
        ViewCache cache = new ViewCache(true);
        AtomicInteger loads = new AtomicInteger();
        IllegalStateException boom = new IllegalStateException("template broken");

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> cache.resolve("home", key -> {
                    loads.incrementAndGet();
                    throw boom;
                }));
        assertSame(boom, thrown, "加载异常必须原样抛出");
        assertSame(VIEW, cache.resolve("home", countingLoader(loads, VIEW)));
        assertEquals(2, loads.get(), "失败结果不可缓存，后续请求应能重试");
    }

    @Test
    void resolve_waiterSeesOriginalFailure() throws Exception {
        ViewCache cache = new ViewCache(true);
        IllegalStateException boom = new IllegalStateException("loader failed");
        CompletableFuture<View> failed = new CompletableFuture<>();
        failed.completeExceptionally(boom);
        viewsOf(cache).put("home", failed);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> cache.resolve("home", key -> VIEW));
        assertSame(boom, thrown, "等待线程应拿到原始异常，而不是 CompletionException");
    }

    @Test
    void resolve_waiterSeesOriginalError() throws Exception {
        ViewCache cache = new ViewCache(true);
        AssertionError fatal = new AssertionError("loader fatal");
        CompletableFuture<View> failed = new CompletableFuture<>();
        failed.completeExceptionally(fatal);
        viewsOf(cache).put("home", failed);

        assertSame(fatal, assertThrows(AssertionError.class, () -> cache.resolve("home", key -> VIEW)));
    }

    @Test
    void resolve_overCapacity_evictsOldestOnly() throws Exception {
        ViewCache cache = new ViewCache(true);
        AtomicInteger loads = new AtomicInteger();
        ViewCache.Loader loader = countingLoader(loads, VIEW);

        for (int i = 0; i <= MAX_SIZE; i++) {
            cache.resolve("v" + i, loader);
        }
        int afterInsert = loads.get();
        assertEquals(MAX_SIZE + 1, afterInsert);

        assertSame(VIEW, cache.resolve("v1", loader));
        assertSame(VIEW, cache.resolve("v512", loader));
        assertSame(VIEW, cache.resolve("v1024", loader));
        assertEquals(afterInsert, loads.get(), "超限只淘汰最早条目，不得清空全表");

        assertSame(VIEW, cache.resolve("v0", loader));
        assertEquals(afterInsert + 1, loads.get(), "最早条目应被淘汰并重新加载");
    }

    @Test
    void invalidate_forcesReload() throws Exception {
        ViewCache cache = new ViewCache(true);
        AtomicInteger loads = new AtomicInteger();
        ViewCache.Loader loader = countingLoader(loads, VIEW);

        assertSame(VIEW, cache.resolve("home", loader));
        cache.invalidate();
        assertSame(VIEW, cache.resolve("home", loader));
        assertEquals(2, loads.get());
    }

    @Test
    void invalidate_afterNegativeResult_allowsNewTemplate() throws Exception {
        ViewCache cache = new ViewCache(true);
        assertNull(cache.resolve("new", key -> null));
        assertNull(cache.resolve("new", key -> VIEW), "失效前负结果仍生效");

        cache.invalidate();
        assertSame(VIEW, cache.resolve("new", key -> VIEW), "运行期新增模板在失效后可见");
    }

    @Test
    void resolve_waiterSeesRawThrowable_wrappedInCompletionException() throws Exception {
        ViewCache cache = new ViewCache(true);
        Throwable raw = new Throwable("neither Exception nor Error");
        CompletableFuture<View> failed = new CompletableFuture<>();
        failed.completeExceptionally(raw);
        viewsOf(cache).put("home", failed);

        CompletionException ex = assertThrows(CompletionException.class,
                () -> cache.resolve("home", key -> VIEW));
        assertSame(raw, ex.getCause());
    }

    @Test
    void trackInsertion_defersInFlightEntry() throws Exception {
        ViewCache cache = new ViewCache(true);
        ConcurrentMap<String, CompletableFuture<View>> views = viewsOf(cache);
        ConcurrentLinkedQueue<String> queue = queueOf(cache);

        views.put("inflight", new CompletableFuture<>());
        queue.add("inflight");
        fillCompleted(views, queue, MAX_SIZE);

        invokeTrackInsertion(cache, "extra");

        assertTrue(views.containsKey("inflight"), "仍在加载的条目不能被淘汰");
        assertTrue(queue.contains("inflight"), "未完成条目应放回队列尾部等待下次淘汰");
    }

    @Test
    void trackInsertion_skipsStaleQueueEntries() throws Exception {
        ViewCache cache = new ViewCache(true);
        ConcurrentMap<String, CompletableFuture<View>> views = viewsOf(cache);
        ConcurrentLinkedQueue<String> queue = queueOf(cache);

        queue.add("stale");
        fillCompleted(views, queue, MAX_SIZE + 1);

        invokeTrackInsertion(cache, "extra");

        assertFalse(views.containsKey("v0"), "陈旧队列项应被跳过，随后淘汰最早的有效条目");
        assertTrue(views.containsKey("v1"));
        assertEquals(MAX_SIZE, views.size());
    }

    @Test
    void trackInsertion_inFlightAtHead_stillEvictsLaterCompletedEntries() throws Exception {
        ViewCache cache = new ViewCache(true);
        ConcurrentMap<String, CompletableFuture<View>> views = viewsOf(cache);
        ConcurrentLinkedQueue<String> queue = queueOf(cache);

        views.put("inflight", new CompletableFuture<>());
        queue.add("inflight");
        fillCompleted(views, queue, MAX_SIZE);

        invokeTrackInsertion(cache, "extra");

        assertTrue(views.containsKey("inflight"), "仍在加载的条目不能被淘汰");
        assertFalse(views.containsKey("v0"), "旧实现遇队首 in-flight 直接 return，会漏掉后续可淘汰条目");
        assertEquals(MAX_SIZE, views.size(), "应收敛到容量上限");
        assertTrue(queue.contains("inflight"), "未完成条目应放回队列尾部");
    }

    @Test
    void invalidate_concurrentWithResolve_keepsCacheUsable() throws Exception {
        ViewCache cache = new ViewCache(true);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        if (id % 2 == 0) {
                            cache.invalidate();
                        } else {
                            cache.resolve("v" + (j % 8), key -> VIEW);
                        }
                    }
                } catch (Throwable ex) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            t.start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(0, failures.get(), "并发失效与解析不应抛异常");
        assertSame(VIEW, cache.resolve("home", key -> VIEW), "失效后缓存仍可用");
    }

    private static void fillCompleted(ConcurrentMap<String, CompletableFuture<View>> views,
            ConcurrentLinkedQueue<String> queue, int count) {
        for (int i = 0; i < count; i++) {
            String key = "v" + i;
            CompletableFuture<View> done = new CompletableFuture<>();
            done.complete(VIEW);
            views.put(key, done);
            queue.add(key);
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, CompletableFuture<View>> viewsOf(ViewCache cache) throws Exception {
        Field field = ViewCache.class.getDeclaredField("views");
        field.setAccessible(true);
        return (ConcurrentMap<String, CompletableFuture<View>>) field.get(cache);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentLinkedQueue<String> queueOf(ViewCache cache) throws Exception {
        Field field = ViewCache.class.getDeclaredField("insertionOrder");
        field.setAccessible(true);
        return (ConcurrentLinkedQueue<String>) field.get(cache);
    }

    private static void invokeTrackInsertion(ViewCache cache, String key) throws Exception {
        Method method = ViewCache.class.getDeclaredMethod("trackInsertion", String.class);
        method.setAccessible(true);
        method.invoke(cache, key);
    }

    private static ViewCache.Loader countingLoader(AtomicInteger loads, View result) {
        return key -> {
            loads.incrementAndGet();
            return result;
        };
    }
}
