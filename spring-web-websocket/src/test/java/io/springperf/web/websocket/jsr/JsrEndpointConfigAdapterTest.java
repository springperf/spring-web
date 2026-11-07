package io.springperf.web.websocket.jsr;

import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsrEndpointConfigAdapterTest {

    @ServerEndpoint(value = "/ws", subprotocols = {"chat", "superchat"})
    static class BasicEndpoint {}

    @ServerEndpoint(value = "/custom", configurator = CustomConfigurator.class)
    static class CustomEndpoint {}

    public static class CustomConfigurator extends ServerEndpointConfig.Configurator {
        static final CustomEndpoint INSTANCE = new CustomEndpoint();
        static final java.util.concurrent.atomic.AtomicInteger INVOCATIONS = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
            if (endpointClass == CustomEndpoint.class) {
                INVOCATIONS.incrementAndGet();
                return endpointClass.cast(INSTANCE);
            }
            return super.getEndpointInstance(endpointClass);
        }
    }

    @Test
    void constructor_copiesMetadataValues() {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(BasicEndpoint.class);
        JsrEndpointConfigAdapter adapter = new JsrEndpointConfigAdapter(metadata);
        assertEquals(BasicEndpoint.class, adapter.getEndpointClass());
        assertEquals("/ws", adapter.getPath());
        assertEquals(Arrays.asList("chat", "superchat"), adapter.getSubprotocols());
        assertTrue(adapter.getExtensions().isEmpty());
    }

    @Test
    void defaultConfigurator_instantiatesEndpoint() throws Exception {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(BasicEndpoint.class);
        JsrEndpointConfigAdapter adapter = new JsrEndpointConfigAdapter(metadata);
        ServerEndpointConfig.Configurator configurator = adapter.getConfigurator();
        BasicEndpoint instance = configurator.getEndpointInstance(BasicEndpoint.class);
        assertNotNull(instance);
        assertEquals(BasicEndpoint.class, instance.getClass(), "默认 Configurator 必须反射构造端点类本身");
    }

    @Test
    void customConfigurator_delegatesGetEndpointInstance() throws Exception {
        CustomConfigurator.INVOCATIONS.set(0);
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(CustomEndpoint.class);
        JsrEndpointConfigAdapter adapter = new JsrEndpointConfigAdapter(metadata);
        ServerEndpointConfig.Configurator configurator = adapter.getConfigurator();
        CustomEndpoint instance = configurator.getEndpointInstance(CustomEndpoint.class);
        assertNotNull(instance);
        assertSame(CustomConfigurator.INSTANCE, instance, "必须委托给用户 Configurator 返回的实例");
        assertEquals(1, CustomConfigurator.INVOCATIONS.get(), "用户 Configurator 必须被实际调用一次");
    }

    /**
     * 规范默认语义是「允许所有来源」；但 javax.websocket 基类实现要 ServiceLoader 找平台 Configurator， 脱离容器会抛 RuntimeException("Cannot load
     * platform configurator")——一旦落到基类，所有握手都会被拒。
     */
    @Test
    void defaultConfigurator_checkOriginAllowsAllWithoutPlatform() {
        JsrEndpointConfigAdapter adapter = new JsrEndpointConfigAdapter(new JsrEndpointMetadata(BasicEndpoint.class));
        ServerEndpointConfig.Configurator configurator = adapter.getConfigurator();
        assertTrue(configurator.checkOrigin(null), "非浏览器客户端不带 Origin，必须放行");
        assertTrue(configurator.checkOrigin("http://any.example"), "默认不限制来源");
    }

    @Test
    void customConfiguratorWithoutCheckOriginOverride_allowsWithoutPlatform() {
        JsrEndpointConfigAdapter adapter = new JsrEndpointConfigAdapter(new JsrEndpointMetadata(CustomEndpoint.class));
        assertTrue(adapter.getConfigurator().checkOrigin(null), "用户 Configurator 未覆写 checkOrigin 时应按默认放行");
    }

    @Test
    void customConfiguratorWithCheckOriginOverride_delegates() {
        JsrEndpointConfigAdapter adapter = new JsrEndpointConfigAdapter(
                new JsrEndpointMetadata(OriginRejectingEndpoint.class));
        assertFalse(adapter.getConfigurator().checkOrigin("http://evil.example"), "用户覆写的 origin 判定必须生效");
    }

    @ServerEndpoint(value = "/origin", configurator = OriginRejectingConfigurator.class)
    static class OriginRejectingEndpoint {
    }

    public static class OriginRejectingConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public boolean checkOrigin(String originHeaderValue) {
            return !"http://evil.example".equals(originHeaderValue);
        }
    }

    /* ==================== 自定义 Configurator 的协商 / 握手扩展点 ==================== */

    @ServerEndpoint(value = "/negotiating", configurator = NegotiatingConfigurator.class)
    static class NegotiatingEndpoint {
    }

    public static class NegotiatingConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public String getNegotiatedSubprotocol(List<String> supported, List<String> requested) {
            return "custom";
        }

        @Override
        public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested) {
            return Arrays.asList();
        }

        @Override
        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            response.getHeaders().put("X-Custom", Arrays.asList("1"));
        }
    }

    @Test
    void customConfigurator_delegatesSubprotocolExtensionAndHandshake() {
        JsrEndpointConfigAdapter adapter = new JsrEndpointConfigAdapter(
                new JsrEndpointMetadata(NegotiatingEndpoint.class));
        ServerEndpointConfig.Configurator configurator = adapter.getConfigurator();

        assertEquals("custom", configurator.getNegotiatedSubprotocol(Arrays.asList("chat"), Arrays.asList("chat")),
                "用户 Configurator 的子协议协商必须生效");
        assertTrue(configurator.getNegotiatedExtensions(Arrays.asList(), Arrays.asList()).isEmpty());

        HandshakeResponse response = new HandshakeResponse() {
            private final Map<String, List<String>> headers = new java.util.HashMap<>();

            @Override
            public Map<String, List<String>> getHeaders() {
                return headers;
            }
        };
        configurator.modifyHandshake(adapter, null, response);
        assertEquals(Arrays.asList("1"), response.getHeaders().get("X-Custom"), "modifyHandshake 必须委托给用户 Configurator");
    }

    @Test
    void userProperties_mutable() {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(BasicEndpoint.class);
        JsrEndpointConfigAdapter adapter = new JsrEndpointConfigAdapter(metadata);
        Map<String, Object> props = adapter.getUserProperties();
        props.put("k", "v");
        assertEquals("v", adapter.getUserProperties().get("k"));
    }

    @Test
    void customConfigurator_instantiationFailure_throwsIllegalState() {
        @ServerEndpoint(value = "/broken", configurator = BrokenConfigurator.class)
        class BrokenEndpoint {}
        assertThrows(IllegalStateException.class,
                () -> new JsrEndpointConfigAdapter(new JsrEndpointMetadata(BrokenEndpoint.class)));
    }

    public static class BrokenConfigurator extends ServerEndpointConfig.Configurator {
        public BrokenConfigurator() {
            throw new RuntimeException("ctor fail");
        }
    }
}
