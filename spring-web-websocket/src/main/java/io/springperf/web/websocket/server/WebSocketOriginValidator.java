package io.springperf.web.websocket.server;

import org.springframework.lang.Nullable;

/**
 * 握手期 Origin 校验扩展点：{@code WebSocketHandler} 实现该接口后，路由层会在全局 / per-path 白名单校验之外， 再调用一次它的判定；任一不通过即拒绝升级（403）。
 * <p>
 * 存在的理由是 JSR-356：{@code ServerEndpointConfig.Configurator#checkOrigin} 在标准容器里由容器调用， 而本框架的握手发生在路由层、不经过 JSR 容器，因此需要一个
 * handler 侧的入口把它接进来。
 * </p>
 *
 * @author huangcanda
 *
 * @since 3.5.8
 */
public interface WebSocketOriginValidator {

    /**
     * @param origin
     *            {@code Origin} 请求头原值；客户端未发送该头时为 {@code null}（非浏览器客户端）
     *
     * @return 是否允许该来源
     */
    boolean isOriginAllowed(@Nullable String origin);
}
