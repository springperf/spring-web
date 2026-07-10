package io.springperf.web.http.support;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public class NettyMultipartWebRequest extends DefaultFullHttpRequest {

    private static final Logger log = LoggerFactory.getLogger(NettyMultipartWebRequest.class);

    protected final HttpPostRequestDecoder decoder;
    protected final List<InterfaceHttpData> interfaceHttpDataList;

    private final MultiValueMap<String, NettyAttributeMessage> parameters = new LinkedMultiValueMap<>();
    private final MultiValueMap<String, MultipartFile> files = new LinkedMultiValueMap<>();
    private final MultiValueMap<String, HttpInputMessagePart> parts = new LinkedMultiValueMap<>();

    private boolean parsed = false;

    public NettyMultipartWebRequest(HttpRequest request, HttpPostRequestDecoder decoder, HttpHeaders trailingHeader) {
        super(request.protocolVersion(), request.method(), request.uri(), Unpooled.buffer(0), request.headers(), trailingHeader);
        this.decoder = decoder;
        this.interfaceHttpDataList = decoder.getBodyHttpDatas();
    }

    protected void parseIfNeeded() {
        if (parsed) {
            return;
        }
        for (InterfaceHttpData interfaceHttpData : interfaceHttpDataList) {
            switch (interfaceHttpData.getHttpDataType()) {
                case Attribute:
                    NettyAttributeMessage attributeMessage = new NettyAttributeMessage((Attribute) interfaceHttpData);
                    parameters.add(interfaceHttpData.getName(), attributeMessage);
                    parts.add(interfaceHttpData.getName(), attributeMessage);
                    break;
                case FileUpload:
                    NettyMultipartFile nettyMultipartFile = new NettyMultipartFile((FileUpload) interfaceHttpData);
                    files.add(interfaceHttpData.getName(), nettyMultipartFile);
                    parts.add(interfaceHttpData.getName(), nettyMultipartFile);
                    break;
                default:
                    break;
            }
        }
        parsed = true;
    }

    public MultiValueMap<String, NettyAttributeMessage> getParameters() {
        parseIfNeeded();
        return parameters;
    }

    public MultiValueMap<String, MultipartFile> getFiles() {
        parseIfNeeded();
        return files;
    }

    public MultiValueMap<String, HttpInputMessagePart> getParts() {
        parseIfNeeded();
        return parts;
    }

    public HttpPostRequestDecoder getDecoder() {
        return decoder;
    }

    public List<InterfaceHttpData> getInterfaceHttpDataList() {
        return interfaceHttpDataList;
    }

    @Override
    public FullHttpRequest retain() {
        for (InterfaceHttpData interfaceHttpData : interfaceHttpDataList) {
            interfaceHttpData.retain();
        }
        return super.retain();
    }

    public boolean release() {
        try {
            for (InterfaceHttpData interfaceHttpData : interfaceHttpDataList) {
                interfaceHttpData.release();
            }
            super.release();
            return true;
        } catch (Exception ignored) {
            log.debug("release failed", ignored);
            return false;
        }
    }
}
