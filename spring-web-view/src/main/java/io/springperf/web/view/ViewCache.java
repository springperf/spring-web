package io.springperf.web.view;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;

/**
 * 视图名到 {@link View} 的解析结果缓存，同时缓存「未解析」负结果。
 * <p>
 * 模板存在性探测（{@code ClassPathResource.exists()}）与 View 构造原本每请求执行，多引擎共存时按 resolver 数量放大。
 * 本缓存把这部分开销移到首次解析；{@code cacheable=false}（对应 {@code spring.*.cache=false}）时完全旁路，
 * 保留开发期热改模板的语义——此时新增模板无需重启即可被发现。
 * </p>
 * <p>
 * 每个 key 以 {@link CompletableFuture} 占位：并发下只有一个线程执行 loader，其余线程等待同一结果；
 * 加载失败会从表中移除占位并唤醒等待者抛出原异常，绝不把失败永久缓存。
 * 容量上限防止视图名由外部输入拼接时无界增长；超限只淘汰最早的已完成条目，不清空全表（避免缓存抖动）。
 * </p>
 * <p>
 * 负结果同样缓存，因此缓存开启时运行期新增的模板需调用 {@link #invalidate()}（或经
 * {@link ViewResolverRegistry#invalidateViewCaches()} 统一触发）后才可见。
 * </p>
 */
public final class ViewCache {

    /** 负结果哨兵：{@link ConcurrentHashMap} 不允许 null value。 */
    static final View UNRESOLVED = (model, req, resp) -> {
    };

    private static final int MAX_SIZE = 1024;

    @FunctionalInterface
    public interface Loader {
        View load(String key) throws Exception;
    }

    private final ConcurrentMap<String, CompletableFuture<View>> views = new ConcurrentHashMap<>();
    /** 插入顺序，仅用于超限时逐条淘汰；不按访问重排，避免渲染热路径上的写操作。 */
    private final ConcurrentLinkedQueue<String> insertionOrder = new ConcurrentLinkedQueue<>();
    private final boolean cacheable;

    public ViewCache(boolean cacheable) {
        this.cacheable = cacheable;
    }

    /**
     * 返回缓存的 View；未解析返回 {@code null}。同一 key 并发解析时只加载一次，所有线程拿到同一实例。
     */
    public View resolve(String key, Loader loader) throws Exception {
        if (!cacheable) {
            return loader.load(key);
        }
        CompletableFuture<View> cached = views.get(key);
        if (cached != null) {
            return await(cached);
        }
        CompletableFuture<View> created = new CompletableFuture<>();
        CompletableFuture<View> winner = views.putIfAbsent(key, created);
        if (winner != null) {
            return await(winner);
        }
        try {
            View loaded = loader.load(key);
            created.complete(loaded == null ? UNRESOLVED : loaded);
            trackInsertion(key);
            return loaded;
        } catch (Exception | Error ex) {
            views.remove(key, created);
            created.completeExceptionally(ex);
            throw ex;
        }
    }

    private static View await(CompletableFuture<View> future) throws Exception {
        try {
            View view = future.join();
            return view == UNRESOLVED ? null : view;
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception e) {
                throw e;
            }
            if (cause instanceof Error e) {
                throw e;
            }
            throw ex;
        }
    }

    private void trackInsertion(String key) {
        insertionOrder.add(key);
        while (views.size() > MAX_SIZE) {
            String oldest = insertionOrder.poll();
            if (oldest == null) {
                return;
            }
            CompletableFuture<View> current = views.get(oldest);
            if (current == null) {
                continue;
            }
            if (!current.isDone() || current.isCompletedExceptionally()) {
                insertionOrder.add(oldest);
                return;
            }
            // 只淘汰这一个 key；淘汰造成的缓存未命中不影响正确性
            views.remove(oldest, current);
        }
    }

    /** 运行期新增/替换模板后主动失效；配置刷新不重建模板引擎，需由调用方显式触发。 */
    public void invalidate() {
        views.clear();
        insertionOrder.clear();
    }
}
