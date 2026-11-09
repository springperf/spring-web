package io.springperf.web.autoconfigure;

import io.springperf.web.batch.BatchRegistry;
import io.springperf.web.context.WebContext;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 对自动配置做真实条件求值（而非 new Config() 直接调 @Bean 方法）。
 * <p>
 * 覆盖 classpath 条件退让、方法级/类级 {@code @ConditionalOnBean}、以及 RuntimeHints 与事件配置的解耦。
 * 这些配置均不创建 NettyHttpServer，因此不会启动端口。
 * </p>
 */
class AutoConfigurationConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    @Test
    void servletBridge_backsOffWhenServletModuleIsAbsent() {
        runner.withConfiguration(AutoConfigurations.of(SpringWebServletAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(
                        "io.springperf.web.support.servlet.context.ServletAdapterContext"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SpringWebServletAutoConfiguration.class);
                });
    }

    @Test
    void batch_backsOffWhenBatchModuleIsAbsent() {
        runner.withConfiguration(AutoConfigurations.of(SpringWebBatchAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("io.springperf.web.batch.annotation.BatchMapping"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SpringWebBatchAutoConfiguration.class);
                });
    }

    @Test
    void batchRegistry_requiresWebContextBean() {
        runner.withConfiguration(AutoConfigurations.of(SpringWebBatchAutoConfiguration.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SpringWebBatchAutoConfiguration.class);
            assertThat(context).doesNotHaveBean(BatchRegistry.class);
        });

        runner.withConfiguration(AutoConfigurations.of(SpringWebBatchAutoConfiguration.class))
                .withBean(WebContext.class, () -> mock(WebContext.class)).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BatchRegistry.class);
                });
    }

    @Test
    void propertyRefreshListener_requiresWebContextBean() {
        runner.withConfiguration(AutoConfigurations.of(SpringWebPropertyRefreshAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SpringWebPropertyRefreshAutoConfiguration.class);
                });

        runner.withConfiguration(AutoConfigurations.of(SpringWebPropertyRefreshAutoConfiguration.class))
                .withBean(WebContext.class, () -> mock(WebContext.class)).run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(SpringWebPropertyRefreshAutoConfiguration.class);
                });
    }

    @Test
    void runtimeHintsConfiguration_loadsWithoutEventConfiguration() {
        runner.withConfiguration(AutoConfigurations.of(SpringWebRuntimeHintsConfiguration.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SpringWebRuntimeHintsConfiguration.class);
            assertThat(context).doesNotHaveBean(WebServerInitializedEventAutoConfiguration.class);
        });
    }
}
