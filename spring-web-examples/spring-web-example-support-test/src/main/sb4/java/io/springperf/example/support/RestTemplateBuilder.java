package io.springperf.example.support;

import java.time.Duration;

/**
 * 跨版本 {@code RestTemplateBuilder} 薄封装（Spring Boot 4 版）。
 * <p>
 * Boot 4 把 {@code RestTemplateBuilder} 移到 {@code org.springframework.boot.restclient}
 * （artifact：{@code spring-boot-restclient}）。与 SB3 版同名同包，由 profile 选择本 source root。
 * </p>
 * <p>
 * <b>为什么用组合而非继承</b>：父类不可变，{@code rootUri/connectTimeout/...} 都返回新实例；
 * 靠继承覆写会丢失返回值（实测报 "URI with undefined scheme"）。故持有一个父实例，每个方法
 * 更新它并返回 {@code this}。详见 SB3 版 Javadoc。
 * </p>
 * <p>
 * 超时方法名两版本不同：SB3 是 {@code setConnectTimeout/setReadTimeout}，
 * SB4 是 {@code connectTimeout/readTimeout}；本 shim 统一为
 * {@link #connectTimeout}/{@link #readTimeout}。
 * </p>
 */
public class RestTemplateBuilder {

    private org.springframework.boot.restclient.RestTemplateBuilder delegate =
            new org.springframework.boot.restclient.RestTemplateBuilder();

    /** 设置根 URI。 */
    public RestTemplateBuilder rootUri(String rootUri) {
        this.delegate = this.delegate.rootUri(rootUri);
        return this;
    }

    /** 跨版本统一名：设置连接超时（SB4 转发到 connectTimeout）。 */
    public RestTemplateBuilder connectTimeout(Duration timeout) {
        this.delegate = this.delegate.connectTimeout(timeout);
        return this;
    }

    /** 跨版本统一名：设置读取超时（SB4 转发到 readTimeout）。 */
    public RestTemplateBuilder readTimeout(Duration timeout) {
        this.delegate = this.delegate.readTimeout(timeout);
        return this;
    }

    /** 供 {@code TestRestTemplate} 构造器使用的底层 builder。 */
    public org.springframework.boot.restclient.RestTemplateBuilder unwrap() {
        return this.delegate;
    }
}
