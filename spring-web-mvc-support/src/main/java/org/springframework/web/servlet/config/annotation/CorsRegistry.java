package org.springframework.web.servlet.config.annotation;

import java.util.ArrayList;
import java.util.List;

/**
 * A data-collecting shim of Spring MVC's {@code CorsRegistry}.
 * Stores CORS registrations so that they can be read and bridged
 * to the framework's native {@link io.springperf.web.core.cors.CorsRegistry}.
 */
public class CorsRegistry {

    private final List<CorsRegistration> registrations = new ArrayList<>();

    /**
     * Add a CORS mapping for the specified path pattern.
     * @param pathPattern the path pattern to map CORS configuration to
     * @return a {@link CorsRegistration} to customize
     */
    public CorsRegistration addMapping(String pathPattern) {
        CorsRegistration registration = new CorsRegistration(pathPattern);
        this.registrations.add(registration);
        return registration;
    }

    /**
     * Returns all registered CORS registrations.
     */
    public List<CorsRegistration> getRegistrations() {
        return this.registrations;
    }
}
