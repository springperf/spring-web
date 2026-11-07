package io.springperf.web.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for OpenAPI document metadata.
 * <p>
 * Prefix: {@code springperf.openapi}
 * </p>
 */
@ConfigurationProperties(prefix = "springperf.openapi")
public class OpenApiProperties {

    /** API document title. */
    private String title = "Spring Perf Web API";

    /** API document version. */
    private String version = "1.0.0";

    /** API document description. */
    private String description = "Spring Perf Web API";

    /** Whether the OpenAPI integration (customizer, docs endpoint and Swagger UI) is enabled. */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
