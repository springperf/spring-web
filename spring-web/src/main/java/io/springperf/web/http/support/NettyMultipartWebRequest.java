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
            // 注意：destroy() 内 cleanRequestHttpData 对 part data 的 release 是【无守卫】的，
            // 故本方法必须在 data 尚持 refCnt=1 时先行 destroy（见下方 last 分支）。
            boolean last = super.release();
            if (last) {
                // 销毁 decoder：释放 undecodedChunk 池化缓冲与磁盘临时文件。
                // 必须【先于】下方 parts 循环执行：destroy() 内部 cleanRequestHttpData 会对
                // requestFileDeleteMap 中仍持 refCnt=1 的 part data 做无守卫 release（归零并
                // 清理磁盘临时文件），再释放 undecodedChunk。若先循环把 part data 释放到
                // refCnt=0，cleanRequestHttpData 会对 refCnt=0 的 data 做无守卫 release 抛
                // IllegalReferenceCountException，destroy() 在 cleanFiles 处中断，
                // undecodedChunk 池化缓冲每请求泄漏（P1：74575a5 复辟 16d0f55 的修复）。
                // last=true 保证业务读取线程均已结束（其 release 已发生），destroy 不破坏并发读取。
                if (decoder != null) {
                    decoder.destroy();
                }
            }
            // 兜底对称递减：destroy 只覆盖 requestFileDeleteMap 内 part；此处补齐非 map 内
            // data（如 InternalAttribute）及 retain 未配平的额外引用。
            // refCnt()>0 守卫保证幂等（destroy 已释放的 data 在此跳过，重复 release 亦安全）。
            for (InterfaceHttpData interfaceHttpData : interfaceHttpDataList) {
                if (interfaceHttpData.refCnt() > 0) {
                    interfaceHttpData.release();
                }
            }
            return last;
        } catch (Exception ignored) {
            log.debug("release failed", ignored);
            return false;
        }
    }
}
