package io.springperf.web.support.servlet.context;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@link FilterRegistration.Dynamic} 实现。
 * <p>
 * 只在 ServletContext 初始化窗口内登记；Phase3 由 {@code SupportWebFilterRegistry} 认领，转换成框架的 {@code WebFilterRegistration} 并追加到
 * filter 链尾部。
 * </p>
 * <p>
 * 只支持 {@link DispatcherType#REQUEST} 与「追加到末尾」（{@code isMatchAfter=true}）：本框架没有 FORWARD/ERROR 派发， filter 顺序由
 * {@code @Order} 固定，无法在初始化后把新 filter 插到已有 filter 之前。 遇到这两种诉求直接抛 {@link UnsupportedOperationException}，而不是接受配置后给出错误顺序。
 * </p>
 */
public class DynamicFilterRegistration implements FilterRegistration.Dynamic {

    private final String name;

    private final Filter filter;

    private final String className;

    private final Map<String, String> initParameters = new LinkedHashMap<>(4);

    private final Set<String> urlPatterns = new LinkedHashSet<>(4);

    /** 追加在已有 filter 之后：排序时永远排在链尾。 */
    private int order = Integer.MAX_VALUE;

    private volatile boolean asyncSupported;

    private volatile boolean claimed;

    DynamicFilterRegistration(String name, Filter filter) {
        this.name = name;
        this.filter = filter;
        this.className = filter.getClass().getName();
    }

    @Override
    public void addMappingForUrlPatterns(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter,
            String... urlPatterns) {
        requireRequestOnly(dispatcherTypes);
        if (!isMatchAfter) {
            throw new UnsupportedOperationException("addMappingForUrlPatterns(isMatchAfter=false) is not supported: "
                    + "this framework orders filters by @Order and can only append a dynamically registered filter "
                    + "to the end of the chain.");
        }
        if (urlPatterns == null || urlPatterns.length == 0) {
            throw new IllegalArgumentException("urlPatterns must not be null or empty");
        }
        for (String urlPattern : urlPatterns) {
            this.urlPatterns.add(DynamicServletRegistration.validatePattern(urlPattern));
        }
    }

    @Override
    public void addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter,
            String... servletNames) {
        requireRequestOnly(dispatcherTypes);
        // 框架路由是「路径规则」表，没有 servlet name 到请求的映射关系；按名映射无法落地
        throw new UnsupportedOperationException("addMappingForServletNames is not supported: this framework routes "
                + "requests by path rule, not by servlet name. Use addMappingForUrlPatterns instead.");
    }

    @Override
    public Collection<String> getServletNameMappings() {
        return Collections.emptySet();
    }

    @Override
    public Collection<String> getUrlPatternMappings() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(urlPatterns));
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getClassName() {
        return className;
    }

    @Override
    public boolean setInitParameter(String name, String value) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Init parameter name must not be null or empty");
        }
        if (value == null) {
            throw new IllegalArgumentException("Init parameter value must not be null: " + name);
        }
        if (initParameters.containsKey(name)) {
            return false;
        }
        initParameters.put(name, value);
        return true;
    }

    @Override
    public String getInitParameter(String name) {
        return initParameters.get(name);
    }

    @Override
    public Set<String> setInitParameters(Map<String, String> initParameters) {
        if (initParameters == null) {
            throw new IllegalArgumentException("initParameters must not be null");
        }
        Set<String> conflicts = new LinkedHashSet<>();
        for (Map.Entry<String, String> entry : initParameters.entrySet()) {
            if (!setInitParameter(entry.getKey(), entry.getValue())) {
                conflicts.add(entry.getKey());
            }
        }
        return conflicts.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(conflicts);
    }

    @Override
    public Map<String, String> getInitParameters() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(initParameters));
    }

    @Override
    public void setAsyncSupported(boolean isAsyncSupported) {
        this.asyncSupported = isAsyncSupported;
    }

    private static void requireRequestOnly(EnumSet<DispatcherType> dispatcherTypes) {
        if (dispatcherTypes == null || dispatcherTypes.isEmpty()) {
            return;
        }
        if (dispatcherTypes.size() == 1 && dispatcherTypes.contains(DispatcherType.REQUEST)) {
            return;
        }
        throw new UnsupportedOperationException(
                "Only DispatcherType.REQUEST is supported by this framework, got: " + dispatcherTypes);
    }

    /** 追加位置（恒为链尾；{@code SupportWebFilterRegistry} 用它设置 order）。 */
    public int getOrder() {
        return order;
    }

    boolean isAsyncSupported() {
        return asyncSupported;
    }

    /** 被登记的 filter 实例（{@code SupportWebFilterRegistry} 认领后包装成框架 filter）。 */
    public Filter getFilter() {
        return filter;
    }

    boolean isClaimed() {
        return claimed;
    }

    /** 原子认领：返回 true 表示本次调用方负责初始化 filter 并追加到链上。 */
    synchronized boolean markClaimedIfUnclaimed() {
        if (claimed) {
            return false;
        }
        claimed = true;
        return true;
    }

    /** 没有映射的 filter 不会生效；返回 true 表示需要告警。 */
    boolean hasNoMapping() {
        return urlPatterns.isEmpty();
    }
}
