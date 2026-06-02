package io.springperf.web.core.cors;

import io.springperf.web.context.WebComponent;
import io.springperf.web.util.PathPatternUtils;
import io.springperf.web.util.support.ContainmentResult;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;

import java.util.Arrays;

public class CorsRegistration implements WebComponent {

    private final String pathPattern;

    private final CorsConfiguration config;

    private Integer order = Ordered.LOWEST_PRECEDENCE - 10000;


    public CorsRegistration(String pathPattern) {
        this.pathPattern = pathPattern;
        // Same implicit default values as the @CrossOrigin annotation + allows simple methods
        this.config = new CorsConfiguration().applyPermitDefaultValues();
    }

    /**
     * 使用预构建的 {@link CorsConfiguration} 创建注册项。
     * <p>适用于从 {@code CorsEndpointProperties} 等外部配置源导入 CORS 配置的场景。
     * 注意：不会调用 {@code applyPermitDefaultValues()}，调用方需确保配置完整。</p>
     */
    public CorsRegistration(String pathPattern, CorsConfiguration corsConfiguration) {
        this.pathPattern = pathPattern;
        this.config = corsConfiguration;
    }

    public CorsRegistration order(int order) {
        this.order = order;
        return this;
    }

    @Override
    public int getOrder() {
        return this.order;
    }

    /**
     * The list of allowed origins that be specific origins, e.g.
     * {@code "https://domain1.com"}, or {@code "*"} for all origins.
     * <p>A matched origin is listed in the {@code Access-Control-Allow-Origin}
     * response header of preflight actual CORS requests.
     * <p>By default, all origins are allowed.
     * <p><strong>Note:</strong> CORS checks use values from "Forwarded"
     * (<a href="https://tools.ietf.org/html/rfc7239">RFC 7239</a>),
     * "X-Forwarded-Host", "X-Forwarded-Port", and "X-Forwarded-Proto" headers,
     * if present, in order to reflect the client-originated address.
     * Consider using the {@code ForwardedHeaderFilter} in order to choose from a
     * central place whether to extract and use, or to discard such headers.
     * See the Spring Framework reference for more on this filter.
     */
    public CorsRegistration allowedOrigins(String... origins) {
        this.config.setAllowedOrigins(Arrays.asList(origins));
        return this;
    }


    /**
     * Set the HTTP methods to allow, e.g. {@code "GET"}, {@code "POST"}, etc.
     * <p>The special value {@code "*"} allows all methods.
     * <p>By default "simple" methods {@code GET}, {@code HEAD}, and {@code POST}
     * are allowed.
     */
    public CorsRegistration allowedMethods(String... methods) {
        this.config.setAllowedMethods(Arrays.asList(methods));
        return this;
    }

    /**
     * Set the list of headers that a pre-flight request can list as allowed
     * for use during an actual request.
     * <p>The special value {@code "*"} may be used to allow all headers.
     * <p>A header name is not required to be listed if it is one of:
     * {@code Cache-Control}, {@code Content-Language}, {@code Expires},
     * {@code Last-Modified}, or {@code Pragma} as per the CORS spec.
     * <p>By default all headers are allowed.
     */
    public CorsRegistration allowedHeaders(String... headers) {
        this.config.setAllowedHeaders(Arrays.asList(headers));
        return this;
    }

    /**
     * Set the list of response headers other than "simple" headers, i.e.
     * {@code Cache-Control}, {@code Content-Language}, {@code Content-Type},
     * {@code Expires}, {@code Last-Modified}, or {@code Pragma}, that an
     * actual response might have and can be exposed.
     * <p>The special value {@code "*"} allows all headers to be exposed for
     * non-credentialed requests.
     * <p>By default this is not set.
     */
    public CorsRegistration exposedHeaders(String... headers) {
        this.config.setExposedHeaders(Arrays.asList(headers));
        return this;
    }

    /**
     * Whether the browser should send credentials, such as cookies along with
     * cross domain requests, to the annotated endpoint. The configured value is
     * set on the {@code Access-Control-Allow-Credentials} response header of
     * preflight requests.
     * <p><strong>NOTE:</strong> Be aware that this option establishes a high
     * level of trust with the configured domains and also increases the surface
     * attack of the web application by exposing sensitive user-specific
     * information such as cookies and CSRF tokens.
     * <p>By default this is not set in which case the
     * {@code Access-Control-Allow-Credentials} header is also not set and
     * credentials are therefore not allowed.
     */
    public CorsRegistration allowCredentials(boolean allowCredentials) {
        this.config.setAllowCredentials(allowCredentials);
        return this;
    }

    /**
     * Configure how long in seconds the response from a pre-flight request
     * can be cached by clients.
     * <p>By default this is set to 1800 seconds (30 minutes).
     */
    public CorsRegistration maxAge(long maxAge) {
        this.config.setMaxAge(maxAge);
        return this;
    }

    protected String getPathPattern() {
        return this.pathPattern;
    }

    protected CorsConfiguration getCorsConfiguration() {
        return this.config;
    }


    protected ContainmentResult matchPathRuleToCached(String pathRule) {
        return PathPatternUtils.patternContains(pathPattern, pathRule);
    }
}
