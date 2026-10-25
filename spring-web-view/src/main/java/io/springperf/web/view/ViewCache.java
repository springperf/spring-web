package io.springperf.web.view;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 视图名到 {@link View} 的解析结果缓存，同时缓存「未解析」负结果。
 * <p>
 * 模板存在性探测（{@code ClassPathResource.exists()}）与 View 构造原本每请求执行，多引擎共存时按 resolver 数量放大。
 * 本缓存把这部分开销移到首次解析；{@code cacheable=false}（对应 {@code spring.*.cache=false}）时完全旁路，
 * 保留开发期热改模板的语义——此时新增模板无需重启即可被发现。
 * </p>
 * <p>
 * 负结果同样缓存，因此缓存开启时运行期新增的模板要等重启或关闭模板缓存后才可见。
 * 容量上限防止视图名由外部输入拼接时无界增长。
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

    private final ConcurrentMap<String, View> views = new ConcurrentHashMap<>();
    private final boolean cacheable;

    public ViewCache(boolean cacheable) {
        this.cacheable = cacheable;
    }

    /**
     * 返回缓存的 View；未解析返回 {@code null}。并发下同一 key 可能被多个线程各自解析一次，
     * 但结果等价且最终只保留一个实例，不引入锁竞争。
     */
    public View resolve(String key, Loader loader) throws Exception {
        if (!cacheable) {
            return loader.load(key);
        }
        View cached = views.get(key);
        if (cached != null) {
            return cached == UNRESOLVED ? null : cached;
        }
        if (views.size() >= MAX_SIZE) {
            views.clear();
        }
        View loaded = loader.load(key);
        views.putIfAbsent(key, loaded == null ? UNRESOLVED : loaded);
        return loaded;
    }
}
