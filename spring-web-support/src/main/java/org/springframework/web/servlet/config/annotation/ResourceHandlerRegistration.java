package org.springframework.web.servlet.config.annotation;

import org.springframework.http.CacheControl;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A data-collecting shim of Spring MVC's {@code ResourceHandlerRegistration}.
 * Collects resource handler configuration so that it can be read and bridged
 * to the framework's native {@link io.springperf.web.core.resource.ResourceHandlerRegistration}.
 */
public class ResourceHandlerRegistration {

    private final String[] pathPatterns;

    private final List<String> locationValues = new ArrayList<>();

    @Nullable
    private Integer cachePeriod;

    @Nullable
    private CacheControl cacheControl;

    public ResourceHandlerRegistration(String... pathPatterns) {
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
        return this.pathPatterns;
    }

    public List<String> getLocationValues() {
        return this.locationValues;
    }

    @Nullable
    public Integer getCachePeriod() {
        return this.cachePeriod;
    }

    @Nullable
    public CacheControl getCacheControl() {
        return this.cacheControl;
    }
}
