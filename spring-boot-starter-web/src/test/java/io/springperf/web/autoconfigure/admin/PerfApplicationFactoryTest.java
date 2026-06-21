package io.springperf.web.autoconfigure.admin;

import de.codecentric.boot.admin.client.config.InstanceProperties;
import de.codecentric.boot.admin.client.registration.Application;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.web.server.Ssl;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PerfApplicationFactory} 的单元测试。
 */
class PerfApplicationFactoryTest {

    private InstanceProperties instanceProperties;
    private ManagementServerProperties managementServerProperties;
    private ServerProperties serverProperties;
    private WebEndpointProperties webEndpointProperties;
    private Environment environment;

    @BeforeEach
    void setUp() {
        instanceProperties = new InstanceProperties();
        managementServerProperties = new ManagementServerProperties();
        serverProperties = new ServerProperties();
        webEndpointProperties = new WebEndpointProperties();
        environment = mock(Environment.class);

        // InstanceProperties 默认 name="spring-boot-application"，覆盖为 null 以触发 Environment 兜底
        instanceProperties.setName(null);
        when(environment.getProperty("spring.application.name", "application"))
                .thenReturn("test-app");
    }

    private PerfApplicationFactory createFactory() {
        return new PerfApplicationFactory(instanceProperties, managementServerProperties,
                serverProperties, webEndpointProperties, environment);
    }

    // ---------------------------------------------------------------
    // 基本应用名称
    // ---------------------------------------------------------------

    @Test
    @DisplayName("应该使用 InstanceProperties 中配置的应用名称")
    void shouldUseConfiguredName() {
        instanceProperties.setName("my-custom-app");
        Application app = createFactory().createApplication();
        assertThat(app.getName()).isEqualTo("my-custom-app");
    }

    @Test
    @DisplayName("应该使用 spring.application.name 作为默认名称")
    void shouldUseSpringApplicationName() {
        Application app = createFactory().createApplication();
        assertThat(app.getName()).isEqualTo("test-app");
    }

    // ---------------------------------------------------------------
    // Service URL
    // ---------------------------------------------------------------

    @Test
    @DisplayName("无特殊配置时 Service URL 应使用默认端口和空 context-path")
    void serviceUrl_default() {
        Application app = createFactory().createApplication();
        assertThat(app.getServiceUrl()).contains(":8080");
        assertThat(app.getServiceUrl()).doesNotContain("/api");
    }

    @Test
    @DisplayName("Service URL 应使用配置的 server.port")
    void serviceUrl_withCustomPort() {
        serverProperties.setPort(9090);
        Application app = createFactory().createApplication();
        assertThat(app.getServiceUrl()).contains(":9090");
    }

    @Test
    @DisplayName("Service URL 应包含 context-path")
    void serviceUrl_withContextPath() {
        serverProperties.setPort(9090);
        serverProperties.getServlet().setContextPath("/api");
        Application app = createFactory().createApplication();
        assertThat(app.getServiceUrl()).endsWith(":9090/api");
    }

    @Test
    @DisplayName("Service URL 应优先使用用户配置的 service-base-url")
    void serviceUrl_shouldUseServiceBaseUrl() {
        instanceProperties.setServiceBaseUrl("http://my-host:9090/my-app");
        Application app = createFactory().createApplication();
        assertThat(app.getServiceUrl()).isEqualTo("http://my-host:9090/my-app");
    }

    @Test
    @DisplayName("Service URL 应优先使用用户配置的完整 service-url")
    void serviceUrl_shouldUseServiceUrl() {
        instanceProperties.setServiceUrl("http://custom:9090/app");
        Application app = createFactory().createApplication();
        assertThat(app.getServiceUrl()).isEqualTo("http://custom:9090/app");
    }

    // ---------------------------------------------------------------
    // Management URL
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Management URL 默认应与 Service URL 同端口，附加 actuator base-path")
    void managementUrl_default() {
        serverProperties.setPort(9090);
        serverProperties.getServlet().setContextPath("/api");
        Application app = createFactory().createApplication();
        assertThat(app.getManagementUrl()).isEqualTo(app.getServiceUrl() + "/actuator");
    }

    @Test
    @DisplayName("管理端口隔离时 Management URL 不应包含 context-path")
    void managementUrl_withSeparateManagementPort() {
        serverProperties.setPort(9090);
        serverProperties.getServlet().setContextPath("/api");
        managementServerProperties.setPort(9093);

        Application app = createFactory().createApplication();
        assertThat(app.getManagementUrl()).endsWith(":9093/actuator");
        assertThat(app.getManagementUrl()).doesNotContain("/api");
    }

    @Test
    @DisplayName("Management URL 应使用 management-base-url + base-path")
    void managementUrl_shouldUseManagementBaseUrl() {
        instanceProperties.setManagementBaseUrl("http://custom:9093");
        serverProperties.setPort(9090);
        serverProperties.getServlet().setContextPath("/api");

        Application app = createFactory().createApplication();
        assertThat(app.getManagementUrl()).isEqualTo("http://custom:9093/actuator");
    }

    @Test
    @DisplayName("Management URL 应优先使用用户配置的完整 management-url")
    void managementUrl_shouldUseManagementUrl() {
        instanceProperties.setManagementUrl("http://custom:9093/mgmt/health");
        Application app = createFactory().createApplication();
        assertThat(app.getManagementUrl()).isEqualTo("http://custom:9093/mgmt/health");
    }

    // ---------------------------------------------------------------
    // Health URL
    // ---------------------------------------------------------------

    @Test
    @DisplayName("Health URL 默认为 Management URL + /health")
    void healthUrl_default() {
        serverProperties.setPort(9090);
        serverProperties.getServlet().setContextPath("/api");
        Application app = createFactory().createApplication();
        assertThat(app.getHealthUrl()).isEqualTo(app.getManagementUrl() + "/health");
    }

    @Test
    @DisplayName("Health URL 应优先使用用户配置的 health-url")
    void healthUrl_shouldUseHealthUrl() {
        instanceProperties.setHealthUrl("http://custom:9090/api/actuator/health");
        Application app = createFactory().createApplication();
        assertThat(app.getHealthUrl()).isEqualTo("http://custom:9090/api/actuator/health");
    }

    // ---------------------------------------------------------------
    // SSL
    // ---------------------------------------------------------------

    @Test
    @DisplayName("SSL 启用时 scheme 应为 https")
    void scheme_shouldBeHttpsWhenSslEnabled() {
        Ssl ssl = new Ssl();
        ssl.setEnabled(true);
        serverProperties.setSsl(ssl);

        Application app = createFactory().createApplication();
        assertThat(app.getServiceUrl()).startsWith("https://");
    }

    // ---------------------------------------------------------------
    // 全配置组合
    // ---------------------------------------------------------------

    @Test
    @DisplayName("全配置组合：context-path + 管理端口隔离")
    void fullConfig_withContextPathAndManagementPort() {
        serverProperties.setPort(9090);
        serverProperties.getServlet().setContextPath("/api");
        managementServerProperties.setPort(9093);

        Application app = createFactory().createApplication();
        assertThat(app.getServiceUrl()).endsWith(":9090/api");
        assertThat(app.getManagementUrl()).endsWith(":9093/actuator");
        assertThat(app.getHealthUrl()).endsWith(":9093/actuator/health");
    }

    @Test
    @DisplayName("全配置组合：自定义 base-path")
    void fullConfig_withCustomBasePath() {
        serverProperties.setPort(9090);
        webEndpointProperties.setBasePath("/management");

        Application app = createFactory().createApplication();
        assertThat(app.getManagementUrl()).endsWith(":9090/management");
        assertThat(app.getHealthUrl()).endsWith(":9090/management/health");
    }

    @Test
    @DisplayName("用户配置覆盖：所有 URL 都通过配置指定")
    void userOverride_allUrls() {
        instanceProperties.setServiceUrl("http://my-host:9090/my-app");
        instanceProperties.setManagementUrl("http://my-host:9090/my-app/mgmt");
        instanceProperties.setHealthUrl("http://my-host:9090/my-app/mgmt/health");

        Application app = createFactory().createApplication();
        assertThat(app.getServiceUrl()).isEqualTo("http://my-host:9090/my-app");
        assertThat(app.getManagementUrl()).isEqualTo("http://my-host:9090/my-app/mgmt");
        assertThat(app.getHealthUrl()).isEqualTo("http://my-host:9090/my-app/mgmt/health");
    }

    @Test
    @DisplayName("管理端口隔离时 scheme 应使用管理端口 SSL 配置")
    void managementUrl_withManagementSsl() {
        serverProperties.setPort(9090);
        serverProperties.getServlet().setContextPath("/api");
        managementServerProperties.setPort(9093);
        Ssl mgmtSsl = new Ssl();
        mgmtSsl.setEnabled(true);
        managementServerProperties.setSsl(mgmtSsl);

        Application app = createFactory().createApplication();
        assertThat(app.getManagementUrl()).startsWith("https://");
        assertThat(app.getServiceUrl()).startsWith("http://");
    }
}
