package io.springperf.webtest;

import io.springperf.web.context.ApplicationProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * E2E：暴露框架 {@link ApplicationProperties} 读取结果，验证配置动态刷新生效。
 */
@RestController
public class PropertyRefreshTestController {

    /** 用于观测动态刷新的配置键（运行期可读，非启动期固化）。 */
    public static final String OBSERVED_KEY = "test.dynamic.value";

    private final ApplicationProperties props;

    public PropertyRefreshTestController(ApplicationProperties props) {
        this.props = props;
    }

    @GetMapping("/dynamic-prop")
    public Map<String, Object> dynamicProp() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(OBSERVED_KEY, props.get(OBSERVED_KEY, "default"));
        // 框架内置键：验证默认值路径未受清缓存影响
        result.put("asyncTimeout", props.getLong(io.springperf.web.context.PropertiesConstant.ASYNC_REQUEST_TIMEOUT));
        return result;
    }
}
