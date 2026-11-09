package io.springperf.web.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ImportRuntimeHints;

/**
 * 独立承载 GraalVM native-image 可达性提示。
 * <p>
 * 原本挂在 {@link WebServerInitializedEventAutoConfiguration} 上：用户一旦排除事件自动配置，
 * AOT 构建期会连带丢失全部 hints。这里与事件配置解耦，作为独立自动配置注册。
 * </p>
 */
@AutoConfiguration
@ImportRuntimeHints(SpringWebRuntimeHints.class)
public class SpringWebRuntimeHintsConfiguration {
}
