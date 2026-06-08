package io.springperf.web.http;

import java.util.Map;

/**
 * Request-scoped attribute container.
 *
 * <p>Each HTTP request has an associated {@code RequestContext} that lives
 * for the duration of the request. Attributes can be stored and retrieved
 * using either String-based keys (untyped) or typed {@link RequestAttribute}
 * keys (type-safe).</p>
 *
 * <p>This context is the primary mechanism for sharing state between
 * filters, interceptors, argument resolvers, and the controller method
 * within a single request lifecycle.</p>
 *
 * @since 1.0.0
 * @see RequestAttribute
 */
public interface RequestContext {

    /**
     * Return all attributes as a mutable map.
     *
     * @return a map of all attributes (never {@code null})
     */
    Map<String, Object> getAttributes();

    /**
     * Get an attribute by name.
     *
     * @param name the attribute name
     * @return the attribute value, or {@code null} if not found
     */
    Object getAttribute(String name);

    /**
     * Set an attribute by name.
     *
     * @param name the attribute name
     * @param o    the attribute value (may be {@code null} to remove)
     */
    void setAttribute(String name, Object o);

    /**
     * Remove an attribute by name.
     *
     * @param name the attribute name
     * @return the previous value, or {@code null} if none
     */
    Object removeAttribute(String name);

    /**
     * Get a type-safe attribute by its {@link RequestAttribute} key.
     *
     * @param <T> the expected value type
     * @param key the typed attribute key
     * @return the attribute value, or {@code null} if not found
     */
    <T> T getAttribute(RequestAttribute<T> key);

    /**
     * Set a type-safe attribute by its {@link RequestAttribute} key.
     *
     * @param <T>   the value type
     * @param key   the typed attribute key
     * @param value the attribute value
     */
    <T> void setAttribute(RequestAttribute<T> key, T value);
}
