package io.springperf.web.core.resource;

import io.springperf.web.context.WebComponent;
import io.springperf.web.core.mapping.PathMappingContext;
import org.springframework.http.CacheControl;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    public ResourceHandlerRegistration addResourceLocations(String... resourceLocations) {
        this.locationValues.addAll(Arrays.asList(resourceLocations));
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

    protected List<PathMappingContext> buildPathMappingContext() {
        List<PathMappingContext> pathMappingContexts = new ArrayList<>();
        ResourceRequestHandler resourceRequestHandler = getResourceRequestHandler();
        for (String pathPattern : pathPatterns) {
            PathMappingContext pathMappingContext = new PathMappingContext(resourceRequestHandler, pathPattern);
            pathMappingContexts.add(pathMappingContext);
        }
        return pathMappingContexts;
    }

    protected ResourceRequestHandler getResourceRequestHandler() {
        return new ResourceRequestHandler(this);
    }
}
