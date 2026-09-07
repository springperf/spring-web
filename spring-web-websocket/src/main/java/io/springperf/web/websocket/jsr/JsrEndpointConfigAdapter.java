package io.springperf.web.websocket.jsr;

import javax.websocket.Decoder;
import javax.websocket.Encoder;
import javax.websocket.Extension;
import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 浠?{@link JsrEndpointMetadata} 鏋勯€犵殑 {@link ServerEndpointConfig} 瀹炵幇銆?
 *
 * <p>鍏冩暟鎹湪鍚姩鏃惰В鏋愬畬鎴愶紝姝ら€傞厤鍣ㄤ粎鍋氬€兼嫹璐濓紝璇锋眰璺緞涓婃棤鍙嶅皠銆?/p>
 *
 * @author huangcanda
 * @since 3.2.5
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
            // 榛樿 Configurator锛氱洿鎺ユ瀯閫犵鐐瑰疄渚嬶紝閬垮厤渚濊禆瀹瑰櫒骞冲彴 Configurator
            // 锛坖akarta.websocket 榛樿 getEndpointInstance 濮旀墭 ServerContainer 骞冲彴瀹炵幇锛夈€?
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
