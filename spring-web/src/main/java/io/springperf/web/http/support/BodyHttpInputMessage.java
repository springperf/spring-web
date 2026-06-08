package io.springperf.web.http.support;

import org.springframework.http.HttpInputMessage;

/**
 * Extension of Spring's {@link HttpInputMessage} that exposes whether the
 * request body is present.
 *
 * <p>This interface allows the framework to distinguish between a request
 * with an empty body and a request with no body at all, which is important
 * for determining whether {@code @RequestBody}-annotated parameters should
 * be resolved.</p>
 *
 * @since 1.0.0
 * @see HttpInputMessagePart
 */
public interface BodyHttpInputMessage extends HttpInputMessage {

    /**
     * Check whether the request has a body.
     *
     * @return {@code true} if the body is present and non-empty
     */
    boolean hasBody();
}
