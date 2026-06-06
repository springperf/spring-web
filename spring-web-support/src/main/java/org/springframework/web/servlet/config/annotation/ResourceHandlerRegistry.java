package org.springframework.web.servlet.config.annotation;

import java.util.ArrayList;
import java.util.List;

/**
 * A data-collecting shim of Spring MVC's {@code ResourceHandlerRegistry}.
 * Stores resource handler registrations so that they can be read and bridged
 * to the framework's native {@link io.springperf.web.core.resource.ResourceHandlerRegistry}.
 */
public class ResourceHandlerRegistry {

    private final List<ResourceHandlerRegistration> registrations = new ArrayList<>();

    /**
     * Add a resource handler for the specified path patterns.
     * @param pathPatterns one or more resource URL path patterns
     * @return a {@link ResourceHandlerRegistration} to customize
     */
    public ResourceHandlerRegistration addResourceHandler(String... pathPatterns) {
        ResourceHandlerRegistration registration = new ResourceHandlerRegistration(pathPatterns);
        this.registrations.add(registration);
        return registration;
    }

    /**
     * Returns all registered resource handler registrations.
     */
    public List<ResourceHandlerRegistration> getRegistrations() {
        return this.registrations;
    }
}
