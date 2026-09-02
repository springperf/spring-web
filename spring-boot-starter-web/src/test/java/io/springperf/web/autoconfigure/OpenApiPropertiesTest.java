package io.springperf.web.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenApiPropertiesTest {

    @Test
    void defaults() {
        OpenApiProperties props = new OpenApiProperties();
        assertEquals("Spring Perf Web API", props.getTitle());
        assertEquals("1.0.0", props.getVersion());
        assertEquals("Spring Perf Web API", props.getDescription());
    }

    @Test
    void setters() {
        OpenApiProperties props = new OpenApiProperties();
        props.setTitle("My API");
        props.setVersion("2.0.0");
        props.setDescription("desc");
        assertEquals("My API", props.getTitle());
        assertEquals("2.0.0", props.getVersion());
        assertEquals("desc", props.getDescription());
    }
}
