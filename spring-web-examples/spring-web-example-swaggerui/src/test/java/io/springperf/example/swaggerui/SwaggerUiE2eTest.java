package io.springperf.example.swaggerui;

import io.springperf.web.server.NettyHttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = SwaggerUiApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class SwaggerUiE2eTest {

    private TestRestTemplate rest;

    @Autowired
    private NettyHttpServer nettyHttpServer;

    @BeforeEach
    void setUp() {
        int actualPort = nettyHttpServer.getActualPort();
        rest = new TestRestTemplate(new RestTemplateBuilder()
                .rootUri("http://localhost:" + actualPort));
    }

    @Test
    void healthEndpoint() {
        ResponseEntity<Map> resp = rest.getForEntity("/health", Map.class);
        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody().get("status")).isEqualTo("UP");
    }

    @Test
    void swaggerConfig_returnsValidConfig() {
        ResponseEntity<Map> resp = rest.getForEntity("/v3/api-docs/swagger-config", Map.class);

        assertThat(resp.getStatusCodeValue()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().get("url")).isEqualTo("/v3/api-docs");
        assertThat(resp.getBody().get("configUrl")).isEqualTo("/v3/api-docs/swagger-config");
    }

    @Test
    void swaggerUiStaticResources_classpathFound() {
        ClassPathResource resource = new ClassPathResource("META-INF/resources/webjars/swagger-ui/5.2.0/index.html");
        assertThat(resource.exists())
                .as("swagger-ui index.html should exist on classpath").isTrue();
        assertThat(resource.isReadable()).isTrue();
    }

    @Test
    void swaggerUiRedirect_redirectsToIndex() throws Exception {
        java.net.URL url = new java.net.URL("http://localhost:" + nettyHttpServer.getActualPort() + "/swagger-ui.html");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.connect();
        int status = conn.getResponseCode();
        String location = conn.getHeaderField("Location");
        conn.disconnect();

        assertThat(status).isEqualTo(302);
        assertThat(location).isEqualTo("/swagger-ui/index.html");
    }

    @Test
    void swaggerUiStaticResources_served() {
        ResponseEntity<String> resp = rest.getForEntity("/swagger-ui/index.html", String.class);

        int status = resp.getStatusCodeValue();
        if (status == 404) {
            ResponseEntity<String> directResp = new TestRestTemplate().getForEntity(
                    "http://localhost:" + nettyHttpServer.getActualPort() + "/swagger-ui/index.html",
                    String.class);
            System.err.println("Direct /swagger-ui/index.html status: " + directResp.getStatusCodeValue());
        }
        assertThat(status).isEqualTo(200);
        assertThat(resp.getBody()).contains("Swagger UI");
    }
}