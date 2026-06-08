package io.springperf.web.core.mapping.match;

import io.springperf.web.core.mapping.PathMappingContext;
import io.springperf.web.http.WebServerHttpRequest;

/**
 * Contract for matching a request against a specific condition.
 *
 * <p>Matchers represent individual constraints such as HTTP method, request
 * parameters, headers, content type, or produces type. A
 * {@link PathMappingContext} holds a list of matchers that must all pass
 * for the mapping to be selected.</p>
 *
 * <p>The {@link #isSameTypeMatcher} and {@link #haveAmbiguous} methods
 * support disambiguation when multiple mappings match the same request:
 * they determine whether two matchers conflict or overlap ambiguously.</p>
 *
 * @since 1.0.0
 * @see PathMappingContext
 * @see io.springperf.web.core.mapping.match.HttpMethodMatcher
 */
public interface Matcher {

    /**
     * Match a request against this condition.
     *
     * @param req             the incoming HTTP request
     * @param mappingContext  the mapping context being evaluated
     * @return {@code true} if the request satisfies this condition
     */
    boolean match(WebServerHttpRequest req, PathMappingContext mappingContext);

    /**
     * Check whether the given matcher is of the same type as this one.
     *
     * <p>Used to group matchers for disambiguation (e.g., two
     * {@code HttpMethodMatcher} instances conflict on the same path).</p>
     *
     * @param matcher the matcher to compare against
     * @return {@code true} if both matchers are of the same type
     */
    boolean isSameTypeMatcher(Matcher matcher);

    /**
     * Determine whether this matcher is ambiguous with the given one.
     *
     * <p>Two matchers are ambiguous when both could match the same request
     * and the framework cannot deterministically choose between them.</p>
     *
     * @param matcher the matcher to check for ambiguity
     * @return {@code true} if the two matchers are ambiguous
     */
    boolean haveAmbiguous(Matcher matcher);
}


