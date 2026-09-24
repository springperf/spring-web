package io.springperf.web.core.resource;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import io.springperf.web.context.WebComponent;
import io.springperf.web.core.mapping.PathMappingContext;

public class ResourceHandlerRegistration implements WebComponent {

    private final String[] pathPatterns;

    private final List<String> locationValues = new ArrayList<>();

    @Nullable
    private Integer cachePeriod;

    @Nullable
    private CacheControl cacheControl;

    public ResourceHandlerRegistration(String... pathPatterns) {
        Assert.notEmpty(pathPatterns, "At least one path pattern is required for resource handling.");
        this.pathPatterns = pathPatterns;
    }

    @Override
    public String getComponentName() {
        return "ResourceHandlerRegistration:" + String.join(",", pathPatterns);
    }

    public ResourceHandlerRegistration addResourceLocations(String... resourceLocations) {
        for (String location : resourceLocations) {
            if (location.endsWith("/")) {
                location = location.substring(0, location.length() - 1);
            }
            this.locationValues.add(location);
        }
        return this;
    }

    public ResourceHandlerRegistration setCachePeriod(Integer cachePeriod) {
        this.cachePeriod = cachePeriod;
        return this;
    }

    public ResourceHandlerRegistration setCacheControl(CacheControl cacheControl) {
        this.cacheControl = cacheControl;
        return this;
    }

    public String[] getPathPatterns() {
        return pathPatterns;
    }

    public List<String> getLocationValues() {
        return locationValues;
    }

    @Nullable
    public Integer getCachePeriod() {
        return cachePeriod;
    }

    @Nullable
    public CacheControl getCacheControl() {
        return cacheControl;
    }

    public List<PathMappingContext> buildPathMappingContext() {
        return buildPathMappingContext(null);
    }

    /**
     * 构建路径映射，应用全局前缀（对齐 {@code spring.mvc.static-path-pattern}）。
     * <p>
     * 前缀为空或 {@code "/**"} 时按原样注册；否则将前缀与注册 pattern 组合为 {@code prefix-without-/** + pattern}，例如前缀 {@code /resources/**} 与
     * pattern {@code /static/**} 组合为 {@code /resources/static/**}。
     * </p>
     */
    public List<PathMappingContext> buildPathMappingContext(String globalPrefix) {
        List<PathMappingContext> pathMappingContexts = new ArrayList<>();
        ResourceRequestHandler resourceRequestHandler = getResourceRequestHandler();
        String prefix = normalizePrefix(globalPrefix);
        List<String> effectivePatterns = new ArrayList<>();
        for (String pathPattern : pathPatterns) {
            String effective = prefix.isEmpty() ? pathPattern : prefix + pathPattern;
            effectivePatterns.add(effective);
            PathMappingContext pathMappingContext = new PathMappingContext(resourceRequestHandler, effective);
            pathMappingContexts.add(pathMappingContext);
        }
        // 回写实际注册的 pattern（含全局前缀），供 handler 从请求路径剥离前缀解析资源相对路径
        resourceRequestHandler.updateMappedPatterns(effectivePatterns);
        return pathMappingContexts;
    }

    /**
     * 归一化全局前缀：空白与默认 {@code "/**"} 均视为无前缀；否则去掉尾部 {@code /**} 与 {@code /}， 保留以 {@code /} 开头的规范形式。
     */
    static String normalizePrefix(String globalPrefix) {
        if (globalPrefix == null) {
            return "";
        }
        String p = globalPrefix.trim();
        if (p.isEmpty() || "/**".equals(p) || "**".equals(p)) {
            return "";
        }
        if (p.endsWith("/**")) {
            p = p.substring(0, p.length() - 3);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return p;
    }

    protected ResourceRequestHandler getResourceRequestHandler() {
        return new ResourceRequestHandler(this);
    }
}
