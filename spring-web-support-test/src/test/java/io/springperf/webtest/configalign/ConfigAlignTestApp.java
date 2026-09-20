package io.springperf.webtest.configalign;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 配置对齐 E2E 专用应用：仅扫描本包，隔离 {@code io.springperf.webtest.bridge.BridgeE2eConfig}
 * 等注册了自定义 ResourceHandler 的配置——否则 {@code spring.web.resources.add-mappings}
 * 的「无用户注册才自动注册默认映射」语义不会被触发。
 */
@SpringBootApplication(scanBasePackages = {"io.springperf.webtest.configalign"})
public class ConfigAlignTestApp {
}
