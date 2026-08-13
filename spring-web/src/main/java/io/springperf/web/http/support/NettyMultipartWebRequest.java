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
            // 与 retain() 完全对称：每次 release() 都对应地对每个 part data release 一次。
            // 修复前仅在 last=true 时 release 一次：acquire/retain K≥1 次后 data.refCnt
            // 保留 K 个引用不归零（K≥2 时泄漏 K-1），Netty 引用计数 LEAK 检测会告警。
            // refCnt()>0 守卫保证幂等重复 release（destroy 内部的 release 同样有此守卫）。
            // destroy 仍只在 last 时执行：过早 destroy 会 cleanFiles 清空 Attribute 数据，
            // 破坏业务线程读取（历史 bug 根因）。
            boolean last = super.release();
            for (InterfaceHttpData interfaceHttpData : interfaceHttpDataList) {
                if (interfaceHttpData.refCnt() > 0) {
                    interfaceHttpData.release();
                }
            }
            if (last) {
                // 销毁 decoder：释放 undecodedChunk 池化缓冲与磁盘临时文件。
                // destroy() 幂等（对 refCnt<=0 的数据跳过、undecodedChunk 为 null 跳过），
                // 故 retain 多持引用时多次 release 不会双重释放。
                if (decoder != null) {
                    decoder.destroy();
                }
            }
            return last;
        } catch (Exception ignored) {
            log.debug("release failed", ignored);
            return false;
        }
    }
}
