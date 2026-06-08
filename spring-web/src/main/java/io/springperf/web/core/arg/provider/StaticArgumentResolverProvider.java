package io.springperf.web.core.arg.provider;

import io.springperf.web.context.WebComponent;
import io.springperf.web.context.WebContext;
import io.springperf.web.core.arg.StaticArgumentResolver;
import io.springperf.web.core.mapping.MappingHandlerMethod;
import org.springframework.core.MethodParameter;

/**
 * Factory SPI that creates {@link StaticArgumentResolver} instances for
 * annotated controller method parameters.
 *
 * <p>During initialization, the framework iterates over all registered
 * providers and calls {@link #supports} for each handler method parameter.
 * On match, {@link #getResolver} is called to produce a reusable resolver
 * instance that will be invoked at runtime. This effectively caches the
 * resolution strategy per-parameter, avoiding repeated annotation lookups.</p>
 *
 * <p>The separation between provider and resolver exists to distinguish the
 * init-time decision (which resolver strategy applies?) from the runtime
 * execution (resolve the value from the current request).</p>
 *
 * @since 1.0.0
 * @see StaticArgumentResolver
 * @see io.springperf.web.core.arg.RuntimeArgumentResolver
 */
public interface StaticArgumentResolverProvider extends WebComponent {

    /**
     * Check whether this provider can supply a resolver for the given parameter.
     *
     * @param parameter      the method parameter descriptor
     * @param mappingContext the handler method metadata
     * @return {@code true} if this provider can create a resolver for the parameter
     */
    boolean supports(MethodParameter parameter, MappingHandlerMethod mappingContext);

    /**
     * Create a {@link StaticArgumentResolver} for the given parameter.
     *
     * @param parameter      the method parameter descriptor
     * @param mappingContext the handler method metadata
     * @param webContext     the web context for framework service resolution
     * @return a resolver that will be cached and invoked at runtime
     */
    StaticArgumentResolver getResolver(MethodParameter parameter, MappingHandlerMethod mappingContext, WebContext webContext);
}

