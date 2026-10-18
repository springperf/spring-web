package io.springperf.web.http.support;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;

public class NettyMultipartWebRequest extends DefaultFullHttpRequest {

    private static final Logger log = LoggerFactory.getLogger(NettyMultipartWebRequest.class);

    protected final HttpPostRequestDecoder decoder;
    protected final List<InterfaceHttpData> interfaceHttpDataList;

    private final MultiValueMap<String, NettyAttributeMessage> parameters = new LinkedMultiValueMap<>();
    private final MultiValueMap<String, MultipartFile> files = new LinkedMultiValueMap<>();
    private final MultiValueMap<String, HttpInputMessagePart> parts = new LinkedMultiValueMap<>();

    private boolean parsed = false;

    public NettyMultipartWebRequest(HttpRequest request, HttpPostRequestDecoder decoder, HttpHeaders trailingHeader) {
        super(request.protocolVersion(), request.method(), request.uri(), Unpooled.buffer(0), request.headers(),
                trailingHeader);
        this.decoder = decoder;
        this.interfaceHttpDataList = decoder.getBodyHttpDatas();
    }

    /**
     * 把 decoder 解析出的 part 分摊进 parameters / files / parts 三个视图。
     * <p>
     * <b>必须整段加锁</b>：调用方有两条线程路径——{@code NettyServerHttpRequest.parseParameters} 在 {@code synchronized(request)} 内调
     * {@link #getParameters()}，而 {@code getMultiFileMap()} / {@code getPartMap()}（{@code @RequestPart} /
     * {@code MultipartFile} 参数解析）<b>完全无锁</b>。 两者可能在「EventLoop 超时/异常渲染读参数」与「业务线程解析 part 参数」之间重叠：若解析本身不互斥， 两个线程会并发向三个
     * {@code LinkedMultiValueMap} add，且 {@code parsed} 无同步发布—— 表现为重复条目、{@code ConcurrentModificationException} 或半初始化的
     * map。
     * </p>
     * <p>
     * 锁序恒为 request → multipart（或无锁 → multipart），不存在反向持锁，无死锁面。 构建完成后三个 map 只读，故读侧无需再持锁。
     * </p>
     */
    protected void parseIfNeeded() {
        synchronized (this) {
            if (parsed) {
                return;
            }
            for (InterfaceHttpData interfaceHttpData : interfaceHttpDataList) {
                switch (interfaceHttpData.getHttpDataType()) {
                    case Attribute:
                        NettyAttributeMessage attributeMessage = new NettyAttributeMessage(
                                (Attribute) interfaceHttpData);
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
        // 只读视图；字段为 null 时保持原有返回 null 的行为
        return interfaceHttpDataList == null ? null : java.util.Collections.unmodifiableList(interfaceHttpDataList);
    }

    @Override
    public FullHttpRequest retain() {
        for (InterfaceHttpData interfaceHttpData : interfaceHttpDataList) {
            interfaceHttpData.retain();
        }
        return super.retain();
    }

    public boolean release() {
        boolean last;
        try {
            last = super.release();
        } catch (Exception ex) {
            log.debug("super.release() failed", ex);
            return false;
        }
        if (last) {
            // 销毁 decoder：释放 undecodedChunk 池化缓冲与磁盘临时文件。
            // 必须【先于】下方 parts 循环执行：destroy() 内部 cleanRequestHttpData 会对
            // requestFileDeleteMap 中仍持 refCnt=1 的 part data 做无守卫 release（归零并
            // 清理磁盘临时文件），再释放 undecodedChunk。若先循环把 part data 释放到
            // refCnt=0，cleanRequestHttpData 会对 refCnt=0 的 data 做无守卫 release 抛
            // IllegalReferenceCountException，destroy() 在 cleanFiles 处中断，
            // undecodedChunk 池化缓冲每请求泄漏（P1：74575a5 复辟 16d0f55 的修复）。
            // last=true 保证业务读取线程均已结束（其 release 已发生），destroy 不破坏并发读取。
            //
            // destroy() 单独 try/catch：它的异常不得跳过下方兜底循环。除上述顺序问题外，
            // 外部提前释放（业务侧用单参 ByteBufInputStream 关流）也会让 cleanRequestHttpData
            // 抛同一异常——那一次异常曾把整段兜底逻辑连同 undecodedChunk 一起吞掉（P0）。
            try {
                if (decoder != null) {
                    decoder.destroy();
                }
            } catch (Exception ex) {
                log.debug("decoder.destroy() failed, continuing with the fallback loop", ex);
            }
        }
        // 兜底对称递减：destroy 只覆盖 requestFileDeleteMap 内 part；此处补齐非 map 内
        // data（如 InternalAttribute）及 retain 未配平的额外引用。
        // refCnt()>0 守卫保证幂等（destroy 已释放的 data 在此跳过，重复 release 亦安全）。
        try {
            for (InterfaceHttpData interfaceHttpData : interfaceHttpDataList) {
                if (interfaceHttpData.refCnt() > 0) {
                    interfaceHttpData.release();
                }
            }
        } catch (Exception ex) {
            log.debug("part data release failed", ex);
            return false;
        }
        return last;
    }
}
