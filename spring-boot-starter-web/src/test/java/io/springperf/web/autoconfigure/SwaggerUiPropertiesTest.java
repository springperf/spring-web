package io.springperf.web.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SwaggerUiPropertiesTest {

    @Test
    void defaultWebjarVersion() {
        SwaggerUiProperties props = new SwaggerUiProperties();
        assertEquals("5.2.0", props.getWebjarVersion());
    }

    @Test
    void setWebjarVersion() {
        SwaggerUiProperties props = new SwaggerUiProperties();
        props.setWebjarVersion("5.4.0");
        assertEquals("5.4.0", props.getWebjarVersion());
    }
}
