package io.springperf.web.context;

/**
 * Constants for property keys used with {@link ApplicationProperties#get(String, String)}
 * and typed variants.
 */
public final class PropertiesConstant {

    private PropertiesConstant() {
    }

    /** Netty server HTTP port. */
    public static final String SERVER_PORT = "server.port";

    /** Context path of the application. */
    public static final String CONTEXT_PATH = "server.servlet.context-path";

    /** Max content length of an HTTP request in bytes. */
    public static final String HTTP_MAX_CONTENT_LENGTH = "server.http.max-content-length";

    /** HTTP request timeout in milliseconds. */
    public static final String HTTP_TIMEOUT = "server.http.timeout";

    /** Whether to check handler mappings on startup. */
    public static final String CHECK_ON_STARTUP = "server.check-on-startup";
}
