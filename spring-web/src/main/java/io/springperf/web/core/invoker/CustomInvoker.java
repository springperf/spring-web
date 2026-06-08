package io.springperf.web.core.invoker;

import io.springperf.web.core.mapping.match.Matcher;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Extended {@link Invoker} that exposes handler method metadata and matchers.
 *
 * <p>Custom invokers allow user-defined invocation strategies beyond the
 * default reflection-based approach. They carry the handler method reference
 * and optional matchers for request pre-filtering.</p>
 *
 * <p>The {@link #getType()} method returns a discriminator string used for
 * registration and lookup.</p>
 *
 * @since 1.0.0
 * @see Invoker
 * @see io.springperf.web.core.invoker.InvokerRegistry
 */
public interface CustomInvoker extends Invoker {

    /**
     * Return the underlying handler method.
     *
     * @return the handler method
     */
    Method getHandleMethod();

    /**
     * Return the matchers for pre-filtering requests.
     *
     * @return the matcher list (empty if none)
     */
    default List<Matcher> getMatchers() {
        return Collections.emptyList();
    }

    /**
     * Return the type discriminator for this invoker.
     *
     * @return the type string
     */
    String getType();
}
