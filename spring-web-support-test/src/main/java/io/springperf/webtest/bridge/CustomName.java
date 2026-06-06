package io.springperf.webtest.bridge;

/**
 * A simple value type used to verify that custom {@link org.springframework.format.Formatter}
 * registered via {@link org.springframework.web.servlet.config.annotation.WebMvcConfigurer#addFormatters}
 * is correctly bridged and applied during {@code @RequestParam} binding.
 */
public class CustomName {

    private final String value;

    public CustomName(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    @Override
    public String toString() {
        return value;
    }
}
