package io.springperf.web.core.mapping;

/**
 * Mapping 查找结果封装。
 * <p>替代 {@code PathMappingContext} 的 null 返回值，携带三种状态：</p>
 * <ul>
 *   <li><b>matched</b> — 路径 + 所有条件完全匹配</li>
 *   <li><b>pathMatched</b> — 路径精确匹配但条件不匹配（含 methodMismatch 标记）</li>
 *   <li><b>notFound</b> — 完全未匹配</li>
 * </ul>
 */
public class MappingResult {

    private final PathMappingContext matchedContext;
    private final PathMappingContext[] pathMatchedContexts;
    private final boolean methodMismatch;

    private static final MappingResult NOT_FOUND = new MappingResult(null, null, false);
    private static final PathMappingContext[] EMPTY_ARRAY = new PathMappingContext[0];

    private MappingResult(PathMappingContext matchedContext,
                          PathMappingContext[] pathMatchedContexts,
                          boolean methodMismatch) {
        this.matchedContext = matchedContext;
        this.pathMatchedContexts = pathMatchedContexts;
        this.methodMismatch = methodMismatch;
    }

    // ---- factory methods ----

    /** 完全匹配（路径 + 条件均命中） */
    public static MappingResult matched(PathMappingContext ctx) {
        return new MappingResult(ctx, null, false);
    }

    /** 路径精确匹配但条件不满足 */
    public static MappingResult pathMatched(PathMappingContext[] pathMatchedContexts, boolean methodMismatch) {
        return new MappingResult(null, pathMatchedContexts, methodMismatch);
    }

    /** 完全未匹配（路径不存在） */
    public static MappingResult notFound() {
        return NOT_FOUND;
    }

    // ---- query methods ----

    public boolean isMatched() {
        return matchedContext != null;
    }

    public boolean isPathMatched() {
        return pathMatchedContexts != null;
    }

    public boolean isMethodMismatch() {
        return methodMismatch;
    }

    public PathMappingContext getMatchedContext() {
        return matchedContext;
    }

    /**
     * 返回路径命中的所有 PathMappingContext（仅当 {@link #isPathMatched()} 为 true 时非空）。
     * 这些 context 共享相同路径但条件不匹配，可供 CORS 预检等场景读取 {@code @CrossOrigin} 配置。
     */
    public PathMappingContext[] getPathMatchedContexts() {
        return pathMatchedContexts != null ? pathMatchedContexts : EMPTY_ARRAY;
    }
}
