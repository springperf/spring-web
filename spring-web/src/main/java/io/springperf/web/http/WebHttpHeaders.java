package io.springperf.web.http;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

/**
 * {@link HttpHeaders} 的零拷贝 + 缓存增强视图（2.7.x 版本）。
 *
 * <p><b>与 master 的区别：</b>master 版本为兼容 Spring 7.x（SB 4.x，HttpHeaders
 * 不再实现 {@code MultiValueMap}）保留了基于 {@code MethodHandle} 反射的
 * {@code asMultiValueMap()} 桥接；2.7.x 基于 Spring 5.3，{@code HttpHeaders}
 * 原生实现 {@code MultiValueMap}，无需该反射兼容层，此处已移除。</p>
 *
 * <p>保留能力：</p>
 * <ul>
 *   <li>接收 {@code MultiValueMap} 视图的构造器（持有引用而非拷贝，零拷贝）——
 *       配合 {@link NettyHttpHeadersAdapter} 可得到 Netty headers 的零拷贝视图</li>
 *   <li>{@link #getContentType()} 结果缓存，避免重复 {@link MediaType#parseMediaType}</li>
 * </ul>
 */
public class WebHttpHeaders extends HttpHeaders {

    /** 缓存 {@link #getContentType()} 的解析结果，避免重复 {@link MediaType#parseMediaType} */
    private MediaType cachedContentType;

    private static final MediaType NOT_SET = new MediaType("application", "x-not-set");

    public WebHttpHeaders() {
        this.cachedContentType = NOT_SET;
    }

    /**
     * 用已存在的 {@code MultiValueMap} 视图构造，持有引用而非拷贝（零拷贝）。
     * <p>5.3 的 {@code HttpHeaders(MultiValueMap)} 为引用持有
     * （已反编译验证 {@code putfield headers} 无拷贝循环）。传入
     * {@link NettyHttpHeadersAdapter} 即可获得 Netty headers 的只读零拷贝视图。</p>
     */
    public WebHttpHeaders(MultiValueMap<String, String> headers) {
        super(headers);
        this.cachedContentType = NOT_SET;
    }

    @Override
    public MediaType getContentType() {
        if (cachedContentType == NOT_SET) {
            cachedContentType = super.getContentType();
            if (cachedContentType == null) {
                cachedContentType = NOT_SET;
            }
        }
        return cachedContentType == NOT_SET ? null : cachedContentType;
    }

    @Override
    public void setContentType(MediaType mediaType) {
        super.setContentType(mediaType);
        // 清空缓存，下次 getContentType() 重新解析
        cachedContentType = NOT_SET;
    }
}
