package io.springperf.web.websocket.jsr;

import jakarta.websocket.server.ServerEndpoint;
import jakarta.websocket.server.ServerEndpointConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsrEndpointConfigAdapterTest {

    @ServerEndpoint(value = "/ws", subprotocols = {"chat", "superchat"})
    static class BasicEndpoint {}

    @ServerEndpoint(value = "/custom", configurator = CustomConfigurator.class)
    static class CustomEndpoint {}

    public static class CustomConfigurator extends ServerEndpointConfig.Configurator {
        @Override
        public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
            if (endpointClass == CustomEndpoint.class) {
                return endpointClass.cast(new CustomEndpoint());
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
        assertEquals(List.of("chat", "superchat"), adapter.getSubprotocols());
        assertTrue(adapter.getExtensions().isEmpty());
    }

    @Test
    void defaultConfigurator_instantiatesEndpoint() throws Exception {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(BasicEndpoint.class);
        JsrEndpointConfigAdapter adapter = new JsrEndpointConfigAdapter(metadata);
        ServerEndpointConfig.Configurator configurator = adapter.getConfigurator();
        BasicEndpoint instance = configurator.getEndpointInstance(BasicEndpoint.class);
        assertNotNull(instance);
        assertTrue(instance instanceof BasicEndpoint);
    }

    @Test
    void customConfigurator_delegatesGetEndpointInstance() throws Exception {
        JsrEndpointMetadata metadata = new JsrEndpointMetadata(CustomEndpoint.class);
        JsrEndpointConfigAdapter adapter = new JsrEndpointConfigAdapter(metadata);
        ServerEndpointConfig.Configurator configurator = adapter.getConfigurator();
        CustomEndpoint instance = configurator.getEndpointInstance(CustomEndpoint.class);
        assertNotNull(instance);
        assertTrue(instance instanceof CustomEndpoint);
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
