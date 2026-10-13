package io.springperf.web.support.servlet.context;

import io.springperf.web.context.ApplicationProperties;
import io.springperf.web.context.PropertiesConstant;

/**
 * Servlet 编码配置（对齐 Spring Boot {@code server.servlet.encoding.*}），启动期预解析一次。
 *
 * <ul>
 *   <li>{@code charset}：请求/响应统一字符集（默认 UTF-8）</li>
 *   <li>{@code force}：同时强制请求与响应（默认 false）</li>
 *   <li>{@code force-request}：强制请求 charset（默认继承 {@code force}）</li>
 *   <li>{@code force-response}：强制响应 charset（默认继承 {@code force}）</li>
 * </ul>
 *
 * <p>"强制"含义对齐 Boot：为 true 时，即使业务已显式调用 {@code setCharacterEncoding}，
 * 仍以配置的 charset 覆盖。</p>
 */
public final class EncodingConfig {

    /** 默认：UTF-8，均不强制。 */
    public static final EncodingConfig DEFAULT =
            new EncodingConfig(PropertiesConstant.SERVLET_ENCODING_CHARSET_DEFAULT, false, false);

    private final String charset;
    private final boolean forceRequest;
    private final boolean forceResponse;

    public EncodingConfig(String charset, boolean forceRequest, boolean forceResponse) {
        this.charset = charset;
        this.forceRequest = forceRequest;
        this.forceResponse = forceResponse;
    }

    public static EncodingConfig fromProperties(ApplicationProperties props) {
        String charset = props.get(PropertiesConstant.SERVLET_ENCODING_CHARSET,
                PropertiesConstant.SERVLET_ENCODING_CHARSET_DEFAULT);
        boolean force = props.getBoolean(PropertiesConstant.SERVLET_ENCODING_FORCE,
                PropertiesConstant.SERVLET_ENCODING_FORCE_DEFAULT);
        // force-request / force-response 未显式配置时继承 force
        boolean forceRequest = props.getBoolean(PropertiesConstant.SERVLET_ENCODING_FORCE_REQUEST, force);
        boolean forceResponse = props.getBoolean(PropertiesConstant.SERVLET_ENCODING_FORCE_RESPONSE, force);
        return new EncodingConfig(charset, forceRequest, forceResponse);
    }

    public String getCharset() {
        return charset;
    }

    public boolean isForceRequest() {
        return forceRequest;
    }

    public boolean isForceResponse() {
        return forceResponse;
    }
}
