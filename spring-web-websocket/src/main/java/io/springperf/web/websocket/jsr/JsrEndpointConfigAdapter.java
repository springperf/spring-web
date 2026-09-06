package io.springperf.web.websocket.jsr;

import jakarta.websocket.Decoder;
import jakarta.websocket.Encoder;
import jakarta.websocket.Extension;
import jakarta.websocket.HandshakeResponse;
import jakarta.websocket.server.HandshakeRequest;
import jakarta.websocket.server.ServerEndpointConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 从 {@link JsrEndpointMetadata} 构造的 {@link ServerEndpointConfig} 实现。
 *
 * <p>元数据在启动时解析完成，此适配器仅做值拷贝，请求路径上无反射。</p>
 *
 * @author huangcanda
 * @since 3.5.6
 */
public class JsrEndpointConfigAdapter implements ServerEndpointConfig {

    private final Class<?> endpointClass;
    private final String path;
    private final List<String> subprotocols;
    private final List<Class<? extends Decoder>> decoders;
    private final List<Class<? extends Encoder>> encoders;
    private final List<Extension> extensions = Collections.emptyList();
    private final Configurator configurator;
    private final Map<String, Object> userProperties = new ConcurrentHashMap<>();

    public JsrEndpointConfigAdapter(JsrEndpointMetadata metadata) {
        this.endpointClass = metadata.getEndpointClass();
        this.path = metadata.getPath();
        String[] subs = metadata.getSubprotocols();
        this.subprotocols = new ArrayList<>(subs.length);
        Collections.addAll(this.subprotocols, subs);
        this.decoders = metadata.getDecoders();
        this.encoders = metadata.getEncoders();
        this.configurator = newConfigurator(metadata);
    }

    private static Configurator newConfigurator(JsrEndpointMetadata metadata) {
        Class<? extends Configurator> configuratorClass = metadata.getConfiguratorClass();
        if (configuratorClass == null || configuratorClass == Configurator.class) {
            // 默认 Configurator：直接构造端点实例，避免依赖容器平台 Configurator
            // （jakarta.websocket 默认 getEndpointInstance 委托 ServerContainer 平台实现）。
            return new Configurator() {
                @Override
                public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
                    return instantiateEndpoint(endpointClass);
                }
            };
        }
        try {
            final Configurator delegate = configuratorClass.getDeclaredConstructor().newInstance();
            return new Configurator() {
                @Override
                public <T> T getEndpointInstance(Class<T> endpointClass) throws InstantiationException {
                    return delegate.getEndpointInstance(endpointClass);
                }

                @Override
                public String getNegotiatedSubprotocol(List<String> supported, List<String> requested) {
                    return delegate.getNegotiatedSubprotocol(supported, requested);
                }

                @Override
                public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested) {
                    return delegate.getNegotiatedExtensions(installed, requested);
                }

                @Override
                public boolean checkOrigin(String originHeaderValue) {
                    return delegate.checkOrigin(originHeaderValue);
                }

                @Override
                public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request,
                                            HandshakeResponse response) {
                    delegate.modifyHandshake(sec, request, response);
                }
            };
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to instantiate ServerEndpointConfig.Configurator " + configuratorClass.getName()
                            + " for endpoint " + metadata.getEndpointClass().getName(), ex);
        }
    }

    private static <T> T instantiateEndpoint(Class<T> endpointClass) throws InstantiationException {
        try {
            java.lang.reflect.Constructor<T> ctor = endpointClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Exception ex) {
            throw new InstantiationException(
                    "Unable to instantiate endpoint " + endpointClass.getName() + ": " + ex.getMessage());
        }
    }

    @Override
    public Class<?> getEndpointClass() {
        return endpointClass;
    }

    @Override
    public String getPath() {
        return path;
    }

    @Override
    public List<String> getSubprotocols() {
        return subprotocols;
    }

    @Override
    public List<Extension> getExtensions() {
        return extensions;
    }

    @Override
    public Configurator getConfigurator() {
        return configurator;
    }

    @Override
    public List<Class<? extends Decoder>> getDecoders() {
        return decoders;
    }

    @Override
    public List<Class<? extends Encoder>> getEncoders() {
        return encoders;
    }

    @Override
    public Map<String, Object> getUserProperties() {
        return userProperties;
    }
}
