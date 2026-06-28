package io.springperf.web.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostStandardRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import io.springperf.web.context.WebContext;
import io.springperf.web.http.support.HttpInputMessagePart;
import io.springperf.web.http.support.NettyAttributeMessage;
import io.springperf.web.http.support.NettyMultipartWebRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.MultiValueMapAdapter;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
public class NettyServerHttpRequest extends BaseWebServerHttpRequest {

    private final ChannelHandlerContext ctx;
    private final FullHttpRequest request;
    private HttpHeaders headers;
    private URI uri;
    private static final int LARGE_BODY_LIMIT = 4096;
    private static final byte[] EMPTY_BODY = new byte[0];

    private volatile byte[] body;
    private ByteBuf largeBodyBuf;

    /**
     * 使用已解析的 path 构造，跳过 context-path 校验。
     * <p>供管理端口使用——请求 URI 不包含 context-path 前缀时直接使用原始路径。</p>
     */
    public NettyServerHttpRequest(WebContext webContext, ChannelHandlerContext ctx, FullHttpRequest request, String resolvedPath) {
        super(webContext, request.uri(), resolvedPath);
        this.ctx = ctx;
        this.request = request;
    }

    public FullHttpRequest getNativeRequest() {
        return request;
    }

    protected MultiValueMap<String, String> parseParameters() {
        QueryStringDecoder queryStringDecoder = new QueryStringDecoder(getUriStrWithQuery());
        Map<String, List<String>> params = queryStringDecoder.parameters();
        if (request instanceof NettyMultipartWebRequest) {
            NettyMultipartWebRequest multipartWebRequest = (NettyMultipartWebRequest) request;
            MultiValueMap<String, NettyAttributeMessage> attributeMessageMap = multipartWebRequest.getParameters();
            if (!attributeMessageMap.isEmpty()) {
                MultiValueMap<String, String> result = new LinkedMultiValueMap<>();
                for (String name : attributeMessageMap.keySet()) {
                    for (NettyAttributeMessage attr : attributeMessageMap.get(name)) {
                        try {
                            result.add(name, attr.getValue());
                        } catch (Exception e) {
                            log.error("Failed to parse form field: " + attr.getName(), e);
                        }
                    }
                }
                params.forEach((k, values) -> result.addAll(k, values));
                return result;
            }
        }
        String contentType = request.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (contentType != null && contentType.toLowerCase().startsWith(HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString())) {
            HttpPostStandardRequestDecoder decoder = null;
            try {
                decoder = new HttpPostStandardRequestDecoder(new DefaultHttpDataFactory(false), request);
                List<InterfaceHttpData> bodyHttpDataList = decoder.getBodyHttpDatas();
                if (!bodyHttpDataList.isEmpty()) {
                    MultiValueMap<String, String> result = new LinkedMultiValueMap<>();
                    // 遍历解析结果
                    for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
                        if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                            Attribute attr = (Attribute) data;
                            try {
                                result.add(attr.getName(), attr.getValue());
                            } catch (Exception ex) {
                                log.error("Failed to parse form field: " + attr.getName(), ex);
                            }
                        }
                    }
                    params.forEach((k, values) -> result.addAll(k, values));
                    return result;
                }
            } finally {
                if (decoder != null) {
                    // 释放资源，防止内存泄漏
                    decoder.destroy();
                }
            }

        }
        return new MultiValueMapAdapter<>(params);
    }

    @Override
    public MultiValueMap<String, MultipartFile> getMultiFileMap() {
        if (request instanceof NettyMultipartWebRequest) {
            return ((NettyMultipartWebRequest) request).getFiles();
        }
        return null;
    }

    @Override
    public MultiValueMap<String, HttpInputMessagePart> getPartMap() {
        if (request instanceof NettyMultipartWebRequest) {
            return ((NettyMultipartWebRequest) request).getParts();
        }
        return null;
    }

    @Override
    public HttpMethod getMethod() {
        return HttpMethod.valueOf(request.method().name());
    }

    @Override
    public String getMethodValue() {
        return request.method().name();
    }

    @Override
    public HttpHeaders getHeaders() {
        if (headers == null) {
            headers = new HttpHeaders();
            for (String name : request.headers().names()) {
                for (String v : request.headers().getAll(name)) {
                    headers.add(name, v);
                }
            }
        }
        return headers;
    }

    @Override
    public URI getURI() {
        if (uri == null) {
            String scheme = resolveScheme();
            String hostHeader = request.headers().get(HttpHeaderNames.HOST);
            if (hostHeader != null) {
                uri = URI.create(scheme + "://" + hostHeader + getUriStrWithQuery());
            } else {
                InetSocketAddress addr = getLocalAddress();
                uri = URI.create(scheme + "://" + addr.getHostString() + ":" + addr.getPort() + getUriStrWithQuery());
            }
        }
        return uri;
    }

    /**
     * 按优先级确定请求 scheme：
     * <ol>
     *   <li>RFC 7239 {@code Forwarded} 头（{@code proto=https}）</li>
     *   <li>{@code X-Forwarded-Proto} 头（Nginx / AWS ALB 等代理）</li>
     *   <li>Netty pipeline 中存在 {@link SslHandler}（TLS 在 Java 层终结）</li>
     *   <li>兜底 {@code http}</li>
     * </ol>
     */
    private String resolveScheme() {
        // 1. RFC 7239 Forwarded
        String forwarded = request.headers().get("Forwarded");
        if (forwarded != null) {
            String proto = parseForwardedProto(forwarded);
            if (proto != null) {
                return proto;
            }
        }
        // 2. X-Forwarded-Proto
        String forwardedProto = request.headers().get("X-Forwarded-Proto");
        if (forwardedProto != null && !forwardedProto.isEmpty()) {
            return forwardedProto;
        }
        // 3. SSL 在 Java 层终结
        if (ctx.pipeline().get(SslHandler.class) != null) {
            return "https";
        }
        // 4. 兜底
        return "http";
    }

    /**
     * 从 RFC 7239 Forwarded 头中提取 proto 指令。
     * 格式示例: {@code Forwarded: proto=https; host=example.com}
     */
    private static String parseForwardedProto(String forwarded) {
        for (String segment : forwarded.split(";")) {
            segment = segment.trim();
            if (segment.startsWith("proto=") || segment.startsWith("proto =")) {
                // proto 值可能带引号: proto="https" 或 proto=https
                String value = segment.substring(segment.indexOf('=') + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    @Override
    public InputStream getBody() {
        getBodyBytes();
        if (largeBodyBuf != null) {
            return new ByteBufInputStream(largeBodyBuf.duplicate(), false);
        }
        return new ByteArrayInputStream(body);
    }

    protected byte[] getBodyBytes() {
        if (body == null) {
            synchronized (this) {
                if (body == null) {
                    ByteBuf content = request.content();
                    int size = content.readableBytes();
                    if (size <= LARGE_BODY_LIMIT) {
                        body = ByteBufUtil.getBytes(content);
                    } else {
                        largeBodyBuf = content.retainedDuplicate();
                        body = EMPTY_BODY;
                    }
                }
            }
        }
        return body;
    }

    @Override
    public boolean hasBody() {
        return request.content().readableBytes() > 0;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) ctx.channel().localAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) ctx.channel().remoteAddress();
    }

    @Override
    public int getContentLength() {
        return request.content().readableBytes();
    }

    @Override
    public void acquire() {
        ReferenceCountUtil.retain(request);
    }

    @Override
    public boolean release() {
        if (largeBodyBuf != null) {
            largeBodyBuf.release();
            largeBodyBuf = null;
        }
        return ReferenceCountUtil.release(request);
    }

}

