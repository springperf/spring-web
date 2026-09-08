package org.springframework.web.servlet.config.annotation;

import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayList;
import java.util.List;

/**
 * A data-collecting shim of Spring MVC's {@code InterceptorRegistry}.
 * Stores registered interceptors so that they can be read and bridged
 * to the framework's native {@link io.springperf.web.core.interceptor.InterceptorRegistry}.
 */
public class InterceptorRegistry {

    private final List<InterceptorRegistration> registrations = new ArrayList<>();

    /**
     * Adds the provided {@link HandlerInterceptor}.
     * @param interceptor the interceptor to add
     * @return an {@link InterceptorRegistration} to customize
     */
    public InterceptorRegistration addInterceptor(HandlerInterceptor interceptor) {
        InterceptorRegistration registration = new InterceptorRegistration(interceptor);
        this.registrations.add(registration);
        return registration;
    }

    /**
     * Returns all registered interceptor registrations.
     */
    public List<InterceptorRegistration> getRegistrations() {
        return this.registrations;
    }
}
