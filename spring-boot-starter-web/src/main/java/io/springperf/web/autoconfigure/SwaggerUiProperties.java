package io.springperf.web.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Swagger UI auto-configuration.
 * <p>
 * Prefix: {@code springperf.swagger-ui}
 * </p>
 */
@ConfigurationProperties(prefix = "springperf.swagger-ui")
public class SwaggerUiProperties {

    /** 必须与根 pom.xml 的 {@code swagger-ui.version} 一致；由 SwaggerUiPropertiesTest 锁定。 */
    public static final String DEFAULT_WEBJAR_VERSION = "5.2.0";

    /** Whether Swagger UI static resources are served. */
    private boolean enabled = true;

    /**
     * Swagger UI webjar version. Must match the {@code org.webjars:swagger-ui} dependency version (root {@code pom.xml}
     * property {@code swagger-ui.version}) — the two are cross-referenced, not derived, so a version bump has to update
     * both. SwaggerUiPropertiesTest#defaultWebjarVersionMatchesRootPom fails if they drift. Kept as a plain default
     * rather than a filtered resource so the starter jar stays free of build-time templating.
     */
    private String webjarVersion = DEFAULT_WEBJAR_VERSION;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWebjarVersion() {
        return webjarVersion;
    }

    public void setWebjarVersion(String webjarVersion) {
        this.webjarVersion = webjarVersion;
    }
}
