package io.springperf.web.autoconfigure;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SwaggerUiPropertiesTest {

    @Test
    void defaultWebjarVersion() {
        SwaggerUiProperties props = new SwaggerUiProperties();
        assertEquals(SwaggerUiProperties.DEFAULT_WEBJAR_VERSION, props.getWebjarVersion());
        assertTrue(props.isEnabled());
    }

    @Test
    void setWebjarVersion() {
        SwaggerUiProperties props = new SwaggerUiProperties();
        props.setWebjarVersion("5.4.0");
        props.setEnabled(false);
        assertEquals("5.4.0", props.getWebjarVersion());
        assertEquals(false, props.isEnabled());
    }

    /** 锁定 Java 默认值与根 pom.xml 的 webjar 版本一致，避免升级时只改一处。 */
    @Test
    void defaultWebjarVersionMatchesRootPom() throws Exception {
        Path pom = Paths.get("..", "pom.xml");
        if (!Files.isRegularFile(pom)) {
            pom = Paths.get("pom.xml");
        }
        assertTrue(Files.isRegularFile(pom), "cannot locate root pom.xml from module basedir");
        Matcher matcher = Pattern.compile("<swagger-ui\\.version>\\s*([^<]+?)\\s*</swagger-ui\\.version>")
                .matcher(Files.readString(pom, StandardCharsets.UTF_8));
        assertTrue(matcher.find(), "root pom.xml must declare <swagger-ui.version>");
        assertEquals(matcher.group(1), SwaggerUiProperties.DEFAULT_WEBJAR_VERSION);
    }
}
