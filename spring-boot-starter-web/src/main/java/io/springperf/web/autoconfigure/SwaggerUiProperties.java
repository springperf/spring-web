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

    /**
     * Swagger UI webjar version. Must match the {@code org.webjars:swagger-ui} dependency version
     * (root {@code pom.xml} property {@code swagger-ui.version}) — the two are cross-referenced, not
     * derived, so a version bump has to update both. Kept as a plain default rather than a filtered
     * resource so the starter jar stays free of build-time templating.
     */
    private String webjarVersion = "5.2.0";

    public String getWebjarVersion() {
        return webjarVersion;
    }

    public void setWebjarVersion(String webjarVersion) {
        this.webjarVersion = webjarVersion;
    }
}
