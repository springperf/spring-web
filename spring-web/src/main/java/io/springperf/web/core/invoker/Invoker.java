package io.springperf.web.core.invoker;

/**
 * Simple invoker that calls a handler method with resolved arguments.
 *
 * <p>This is the lowest-level invocation contract. Given an array of resolved
 * arguments, it invokes the underlying handler method and returns the result.
 * The default implementation uses reflection; {@link CustomInvoker} allows
 * user-defined invocation strategies.</p>
 *
 * @since 1.0.0
 * @see CustomInvoker
 */
public interface Invoker {

    /**
     * Invoke the handler method with the given arguments.
     *
     * @param args the resolved method arguments
     * @return the return value from the handler method
     * @throws Throwable if invocation fails
     */
    Object invoke(Object[] args) throws Throwable;
}
