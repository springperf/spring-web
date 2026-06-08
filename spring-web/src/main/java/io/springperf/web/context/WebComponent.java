package io.springperf.web.context;

import org.springframework.core.Ordered;

/**
 * Root interface for all web framework components.
 *
 * <p>Every pluggable component in the framework implements this interface,
 * which provides lifecycle hooks for initialization and an ordering mechanism
 * via {@link Ordered}. Components are registered in a {@link WebContext} and
 * can be discovered by type at runtime.</p>
 *
 * @since 1.0.0
 * @see LifecycleWebComponent
 * @see WebContext
 */
public interface WebComponent extends Ordered {

    /**
     * Return a human-readable name for this component.
     *
     * <p>The default implementation returns the simple class name.</p>
     *
     * @return the component name
     */
    default String getComponentName() {
        return getClass().getSimpleName();
    }

    /**
     * Initialize this component with the given {@link WebContext}.
     *
     * <p>Implementations may use this hook to resolve dependencies from the
     * context (other components, Spring beans, configuration properties).
     * Called once after instantiation, before any phase-based initialization.</p>
     *
     * @param webContext the web context providing access to shared framework state
     */
    default void initWithWebContext(WebContext webContext) {

    }

    /**
     * Return the order value of this component.
     *
     * <p>Lower values have higher priority. The default implementation returns
     * {@link Ordered#LOWEST_PRECEDENCE} - 10000, placing the component before
     * most Spring-managed beans but after framework internals.</p>
     *
     * @return the order value
     */
    @Override
    default int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 10000;
    }
}
