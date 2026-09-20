package io.springperf.webtest;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import io.springperf.web.context.WebContext;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * E2E：配置中心动态刷新。
 *
 * <p>链路：修改 Spring {@code Environment} → 发布 Spring Cloud {@code EnvironmentChangeEvent}
 * → {@code SpringWebPropertyRefreshAutoConfiguration} 监听并调用 {@code WebContext.refreshProperties()}
 * → 清空框架配置缓存 → 后续请求读到新值。</p>
 *
 * <p>回归保护：若未清缓存，请求仍返回旧值，断言失败。</p>
 */
class PropertyRefreshE2eTest extends BaseE2ETest {

    private static final String DYNAMIC_KEY = PropertyRefreshTestController.OBSERVED_KEY;
    private static final String SOURCE_NAME = "e2e-dynamic-props";

    @Autowired
    ConfigurableEnvironment environment;

    @Autowired
    ApplicationEventPublisher eventPublisher;

    @Autowired
    WebContext webContext;

    private String observedValue() throws Exception {
        Request request = new Request.Builder()
                .url(url("/api/dynamic-prop"))
                .get()
                .build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            String body = resp.body().string();
            assertTrue(resp.isSuccessful(), "status=" + resp.code() + " body=" + body);
            JSONObject json = JSON.parseObject(body);
            return json.getString(DYNAMIC_KEY);
        }
    }

    private void setDynamicProperty(String value) {
        Map<String, Object> map = Collections.singletonMap(DYNAMIC_KEY, value);
        environment.getPropertySources().remove(SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource(SOURCE_NAME, map));
    }

    private void publishEnvironmentChange() {
        eventPublisher.publishEvent(new EnvironmentChangeEvent(this, Collections.singleton(DYNAMIC_KEY)));
    }

    @Test
    void dynamicProperty_refreshedAfterEnvironmentChangeEvent() throws Exception {
        setDynamicProperty("v1");
        publishEnvironmentChange();
        assertEquals("v1", observedValue(), "刷新后应读到 v1");

        // 仅改 Environment、不发事件：缓存未失效，仍为旧值
        setDynamicProperty("v2");
        assertEquals("v1", observedValue(), "未发事件前应仍为缓存旧值 v1");

        // 发事件触发刷新
        publishEnvironmentChange();
        assertEquals("v2", observedValue(), "刷新后应读到 v2");
    }

    @Test
    void refreshProperties_directCall_alsoRefreshes() throws Exception {
        setDynamicProperty("direct-1");
        webContext.refreshProperties();
        assertEquals("direct-1", observedValue());

        setDynamicProperty("direct-2");
        webContext.refreshProperties();
        assertEquals("direct-2", observedValue());
    }

    @Test
    void builtinDefaultKey_unaffectedByRefresh() throws Exception {
        // 未配置的内置键：刷新前后都应返回默认值（验证回退路径未破坏）
        setDynamicProperty("x");
        publishEnvironmentChange();

        Request request = new Request.Builder().url(url("/api/dynamic-prop")).get().build();
        try (Response resp = CLIENT.newCall(request).execute()) {
            JSONObject json = JSON.parseObject(resp.body().string());
            assertNotNull(json.get("asyncTimeout"), "内置键应始终可读");
        }
    }
}
