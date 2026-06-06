package org.springframework.web.servlet.config.annotation;

import org.springframework.web.cors.CorsConfiguration;

import java.util.Arrays;

/**
 * A data-collecting shim of Spring MVC's {@code CorsRegistration}.
 * Collects CORS configuration so that it can be read and bridged
 * to the framework's native {@link io.springperf.web.core.cors.CorsRegistration}.
 */
public class CorsRegistration {

    private final String pathPattern;

    private final CorsConfiguration config;

    public CorsRegistration(String pathPattern) {
        this.pathPattern = pathPattern;
        this.config = new CorsConfiguration().applyPermitDefaultValues();
    }

    public CorsRegistration allowedOrigins(String... origins) {
        this.config.setAllowedOrigins(Arrays.asList(origins));
        return this;
    }

    public CorsRegistration allowedMethods(String... methods) {
        this.config.setAllowedMethods(Arrays.asList(methods));
        return this;
    }

    public CorsRegistration allowedHeaders(String... headers) {
        this.config.setAllowedHeaders(Arrays.asList(headers));
        return this;
    }

    public CorsRegistration exposedHeaders(String... headers) {
        this.config.setExposedHeaders(Arrays.asList(headers));
        return this;
    }

    public CorsRegistration allowCredentials(boolean allowCredentials) {
        this.config.setAllowCredentials(allowCredentials);
        return this;
    }

    public CorsRegistration maxAge(long maxAge) {
        this.config.setMaxAge(maxAge);
        return this;
    }

    public String getPathPattern() {
        return this.pathPattern;
    }

    public CorsConfiguration getCorsConfiguration() {
        return this.config;
    }
}
